package io.github.randomcodespace.iq.api;

import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.intelligence.evidence.EvidencePack;
import io.github.randomcodespace.iq.intelligence.evidence.EvidencePackAssembler;
import io.github.randomcodespace.iq.intelligence.evidence.EvidencePackRequest;
import io.github.randomcodespace.iq.intelligence.provenance.ArtifactMetadata;
import io.github.randomcodespace.iq.intelligence.query.CapabilityMatrix;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
    private final ArtifactMetadata artifactMetadata;
    private final CodeIqConfig config;

    public IntelligenceController(
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            EvidencePackAssembler assembler,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            ArtifactMetadata artifactMetadata,
            CodeIqConfig config) {
        this.assembler = assembler;
        this.artifactMetadata = artifactMetadata;
        this.config = config;
    }

    /**
     * Assemble an evidence pack for a symbol or file path.
     *
     * <p>At least one of {@code symbol} or {@code file} must be provided.
     * The {@code file} parameter is path-traversal guarded.
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

        // Path traversal guard on file param
        if (file != null && !file.isBlank()) {
            java.nio.file.Path root = java.nio.file.Path.of(config.getRootPath())
                    .toAbsolutePath().normalize();
            java.nio.file.Path resolved = root.resolve(file).normalize();
            if (!resolved.startsWith(root)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Invalid file path: path traversal detected.");
            }
        }

        EvidencePackRequest request = new EvidencePackRequest(symbol, file, maxSnippetLines, includeRefs);
        return assembler.assemble(request, artifactMetadata);
    }

    /**
     * Returns the artifact metadata loaded at serve startup.
     */
    @GetMapping("/manifest")
    public ArtifactMetadata getManifest() {
        if (artifactMetadata == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Artifact metadata unavailable. Run 'enrich' first.");
        }
        return artifactMetadata;
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
}
