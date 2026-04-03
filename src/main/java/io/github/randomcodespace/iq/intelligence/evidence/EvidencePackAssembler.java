package io.github.randomcodespace.iq.intelligence.evidence;

import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.graph.GraphStore;
import io.github.randomcodespace.iq.intelligence.CapabilityLevel;
import io.github.randomcodespace.iq.intelligence.lexical.CodeSnippet;
import io.github.randomcodespace.iq.intelligence.lexical.LexicalQueryService;
import io.github.randomcodespace.iq.intelligence.lexical.LexicalResult;
import io.github.randomcodespace.iq.intelligence.lexical.SnippetStore;
import io.github.randomcodespace.iq.intelligence.provenance.ArtifactMetadata;
import io.github.randomcodespace.iq.intelligence.query.QueryPlan;
import io.github.randomcodespace.iq.intelligence.query.QueryPlanner;
import io.github.randomcodespace.iq.intelligence.query.QueryRoute;
import io.github.randomcodespace.iq.intelligence.query.QueryType;
import io.github.randomcodespace.iq.model.CodeNode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Assembles {@link EvidencePack} instances from query plans and lexical results.
 *
 * <p>Stateless and thread-safe — all state is method-local. Active only in the
 * {@code serving} profile.
 */
@Service
@Profile("serving")
public class EvidencePackAssembler {

    private final LexicalQueryService lexicalQueryService;
    private final SnippetStore snippetStore;
    private final QueryPlanner queryPlanner;
    private final CodeIqConfig config;
    private final GraphStore graphStore;

    public EvidencePackAssembler(LexicalQueryService lexicalQueryService,
                                 SnippetStore snippetStore,
                                 QueryPlanner queryPlanner,
                                 CodeIqConfig config,
                                 GraphStore graphStore) {
        this.lexicalQueryService = lexicalQueryService;
        this.snippetStore = snippetStore;
        this.queryPlanner = queryPlanner;
        this.config = config;
        this.graphStore = graphStore;
    }

    /**
     * Assemble an evidence pack for the given request.
     *
     * <p>When no symbols are found, returns an empty pack with a degradation note
     * rather than throwing an exception.
     *
     * @param request the evidence pack request
     * @param artifactMetadata provenance metadata loaded at serve startup
     * @return a fully-assembled (possibly empty) evidence pack; never {@code null}
     */
    public EvidencePack assemble(EvidencePackRequest request, ArtifactMetadata artifactMetadata) {
        int maxLines = resolveMaxLines(request.maxSnippetLines());
        Path rootPath = Path.of(config.getRootPath()).toAbsolutePath().normalize();

        // Resolve query subject — prefer symbol, fall back to filePath
        String subject = request.symbol() != null && !request.symbol().isBlank()
                ? request.symbol().strip()
                : (request.filePath() != null ? request.filePath().strip() : null);

        if (subject == null) {
            return EvidencePack.empty(artifactMetadata, "No symbol or file path provided.");
        }

        // Determine language from filePath when available (for query planner)
        String language = request.filePath() != null
                ? inferLanguage(request.filePath())
                : "unknown";

        // Plan the query
        QueryPlan plan = queryPlanner.plan(QueryType.FIND_SYMBOL, language);

        // Execute lexical lookup
        List<LexicalResult> lexResults = lexicalQueryService.findByIdentifier(subject);

        if (lexResults.isEmpty()) {
            String degradationNote = buildEmptyNote(subject, plan);
            return EvidencePack.empty(artifactMetadata, degradationNote);
        }

        // Collect matched symbols (deterministic order)
        List<CodeNode> matchedSymbols = lexResults.stream()
                .map(LexicalResult::node)
                .toList();

        // Extract snippets bounded by maxLines
        List<CodeSnippet> snippets = new ArrayList<>();
        for (LexicalResult lr : lexResults) {
            CodeNode node = lr.node();
            Optional<CodeSnippet> snippet = snippetStore.extract(node, rootPath);
            snippet.map(s -> boundSnippet(s, maxLines)).ifPresent(snippets::add);
        }

        // Collect related files (sorted for determinism)
        Set<String> relatedFilesSet = new LinkedHashSet<>();
        for (CodeNode node : matchedSymbols) {
            if (node.getFilePath() != null) relatedFilesSet.add(node.getFilePath());
        }
        List<String> relatedFiles = new ArrayList<>(relatedFilesSet);
        relatedFiles.sort(String::compareTo);

        // References: fetch related symbols from the same files when requested
        List<CodeNode> references = List.of();
        if (request.includeReferences()) {
            references = fetchReferences(matchedSymbols, subject);
        }

        // Build provenance list (parallel to matchedSymbols)
        List<Map<String, Object>> provenanceList = matchedSymbols.stream()
                .map(n -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    if (n.getFilePath() != null) m.put("filePath", n.getFilePath());
                    if (n.getLineStart() != null) m.put("lineStart", n.getLineStart());
                    if (n.getLineEnd() != null) m.put("lineEnd", n.getLineEnd());
                    m.put("kind", n.getKind() != null ? n.getKind().getValue() : "unknown");
                    if (n.getProperties() != null) {
                        n.getProperties().forEach((k, v) -> {
                            if (k.startsWith("prov_") && v != null) m.put(k, v);
                        });
                    }
                    return (Map<String, Object>) m;
                })
                .toList();

        // Degradation notes
        List<String> degradationNotes = buildDegradationNotes(plan);

        // Overall capability level = worst across matched symbols
        CapabilityLevel capLevel = deriveCapabilityLevel(plan);

        return new EvidencePack(
                matchedSymbols,
                relatedFiles,
                references,
                snippets,
                provenanceList,
                degradationNotes,
                artifactMetadata,
                capLevel
        );
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private int resolveMaxLines(Integer requested) {
        int configured = config.getMaxSnippetLines();
        if (requested == null) return configured;
        return Math.min(Math.max(1, requested), configured);
    }

    /**
     * Truncate a snippet to {@code maxLines} lines, centred on the symbol start.
     * Returns the original snippet when already within bounds.
     */
    private CodeSnippet boundSnippet(CodeSnippet snippet, int maxLines) {
        String[] lines = snippet.sourceText().split("\n", -1);
        if (lines.length <= maxLines) return snippet;

        // Take first maxLines lines
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            sb.append(lines[i]).append('\n');
        }
        return new CodeSnippet(
                sb.toString(),
                snippet.filePath(),
                snippet.lineStart(),
                snippet.lineStart() + maxLines - 1,
                snippet.language(),
                snippet.provenance()
        );
    }

    private List<CodeNode> fetchReferences(List<CodeNode> matchedSymbols, String subject) {
        // Traverse CALLS and DEPENDS_ON edges to find nodes that actually reference these symbols
        Set<String> matchedIds = new LinkedHashSet<>();
        for (CodeNode n : matchedSymbols) {
            if (n.getId() != null) matchedIds.add(n.getId());
        }
        Set<String> seen = new LinkedHashSet<>(matchedIds);
        List<CodeNode> references = new ArrayList<>();
        for (CodeNode n : matchedSymbols) {
            if (n.getId() == null) continue;
            for (CodeNode caller : graphStore.findCallers(n.getId())) {
                if (caller.getId() != null && seen.add(caller.getId())) {
                    references.add(caller);
                }
            }
            for (CodeNode dependent : graphStore.findDependents(n.getId())) {
                if (dependent.getId() != null && seen.add(dependent.getId())) {
                    references.add(dependent);
                }
            }
        }
        return references;
    }

    private String buildEmptyNote(String subject, QueryPlan plan) {
        if (plan.route() == QueryRoute.DEGRADED) {
            return plan.degradationNote() != null ? plan.degradationNote()
                    : "Symbol '" + subject + "' not found. Language is not fully supported.";
        }
        return "Symbol '" + subject + "' was not found in the indexed graph.";
    }

    private List<String> buildDegradationNotes(QueryPlan plan) {
        if (plan.degradationNote() != null) {
            return List.of(plan.degradationNote());
        }
        return List.of();
    }

    private CapabilityLevel deriveCapabilityLevel(QueryPlan plan) {
        return switch (plan.route()) {
            case GRAPH_FIRST -> CapabilityLevel.EXACT;
            case MERGED      -> CapabilityLevel.PARTIAL;
            case LEXICAL_FIRST -> CapabilityLevel.LEXICAL_ONLY;
            case DEGRADED    -> CapabilityLevel.UNSUPPORTED;
        };
    }

    private static String inferLanguage(String filePath) {
        if (filePath == null) return "unknown";
        int dot = filePath.lastIndexOf('.');
        if (dot < 0) return "unknown";
        return switch (filePath.substring(dot + 1).toLowerCase()) {
            case "java"              -> "java";
            case "ts", "tsx"         -> "typescript";
            case "js", "jsx"         -> "javascript";
            case "py"                -> "python";
            case "go"                -> "go";
            case "rs"                -> "rust";
            case "cs"                -> "csharp";
            default                  -> "unknown";
        };
    }
}
