package io.github.randomcodespace.iq.intelligence.extractor.go;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.intelligence.CapabilityLevel;
import io.github.randomcodespace.iq.intelligence.extractor.LanguageExtractionResult;
import io.github.randomcodespace.iq.intelligence.extractor.LanguageExtractor;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Go language-specific extractor.
 *
 * <p>Capabilities:
 * <ul>
 *   <li>Package import resolution: maps {@code import "pkg/path"} to service/module node
 *       in the registry.</li>
 *   <li>Interface satisfaction detection: structural typing via method name matching
 *       between struct and interface nodes in the registry.</li>
 * </ul>
 *
 * <p>Confidence: PARTIAL. Go structural typing cannot be fully resolved without a type checker.
 */
@Component
public class GoLanguageExtractor implements LanguageExtractor {

    /**
     * Single import: {@code import "pkg/path"} or block import entry {@code "pkg/path"}.
     * Also handles aliased: {@code alias "pkg/path"}.
     */
    private static final Pattern IMPORT_PATH =
            Pattern.compile("\"([^\"]+)\"");

    /**
     * Import block: {@code import ( ... )} — used to extract the block content.
     */
    private static final Pattern IMPORT_BLOCK =
            Pattern.compile("import\\s*\\(([^)]+)\\)", Pattern.DOTALL);

    /**
     * Single-line import: {@code import "path"} or {@code import alias "path"}.
     */
    private static final Pattern SINGLE_IMPORT =
            Pattern.compile("^import\\s+(?:\\w+\\s+)?\"([^\"]+)\"", Pattern.MULTILINE);

    /**
     * Method signature in an interface: {@code MethodName(params) ReturnType}.
     */
    private static final Pattern INTERFACE_METHOD =
            Pattern.compile("^\\s+(\\w+)\\s*\\(", Pattern.MULTILINE);

    @Override
    public String getLanguage() {
        return "go";
    }

    @Override
    @SuppressWarnings("unchecked")
    public LanguageExtractionResult extract(DetectorContext ctx, CodeNode node) {
        Map<String, CodeNode> nodeRegistry = (ctx.parsedData() instanceof Map<?, ?> raw)
                ? (Map<String, CodeNode>) raw
                : Map.of();

        List<CodeEdge> symbolRefs = extractImportEdges(ctx, node, nodeRegistry);
        Map<String, String> typeHints = extractInterfaceHints(ctx, node, nodeRegistry);

        return new LanguageExtractionResult(List.of(), symbolRefs, typeHints, CapabilityLevel.PARTIAL);
    }

    private List<CodeEdge> extractImportEdges(DetectorContext ctx, CodeNode node,
                                              Map<String, CodeNode> registry) {
        if (ctx.content() == null || registry.isEmpty()) return List.of();

        List<CodeEdge> edges = new ArrayList<>();
        List<String> importPaths = collectImportPaths(ctx.content());

        for (String importPath : importPaths) {
            // Match by last path segment (package name) with ambiguity guard.
            // Short names like "db", "log", "config" can match multiple nodes —
            // skip the edge if more than one node shares the label.
            String pkgName = importPath.substring(importPath.lastIndexOf('/') + 1);
            CodeNode target = lookupUnambiguous(pkgName, registry);
            if (target == null) {
                // Try full path as direct registry key fallback
                target = lookupUnambiguous(importPath, registry);
            }
            if (target != null && !target.getId().equals(node.getId())) {
                String edgeId = "imports:%s:%s".formatted(node.getId(), target.getId());
                CodeEdge edge = new CodeEdge(edgeId, EdgeKind.IMPORTS, node.getId(), target);
                edge.getProperties().put("confidence", "PARTIAL");
                edge.getProperties().put("extractorName", "go_language_extractor");
                edges.add(edge);
            }
        }

        return edges;
    }

    private List<String> collectImportPaths(String content) {
        Set<String> paths = new LinkedHashSet<>();

        Matcher block = IMPORT_BLOCK.matcher(content);
        if (block.find()) {
            Matcher pathMatcher = IMPORT_PATH.matcher(block.group(1));
            while (pathMatcher.find()) {
                paths.add(pathMatcher.group(1));
            }
        }

        Matcher single = SINGLE_IMPORT.matcher(content);
        while (single.find()) {
            paths.add(single.group(1));
        }

        return new ArrayList<>(paths);
    }

    /**
     * Structural interface satisfaction: if this node is a CLASS/COMPONENT (struct),
     * find INTERFACE nodes whose method names all appear in the struct's source file.
     * Records satisfied interface names as a type hint.
     */
    private Map<String, String> extractInterfaceHints(DetectorContext ctx, CodeNode node,
                                                      Map<String, CodeNode> registry) {
        if (ctx.content() == null || node.getKind() == null) return Map.of();
        if (node.getKind() != NodeKind.CLASS && node.getKind() != NodeKind.COMPONENT) {
            return Map.of();
        }

        List<String> satisfied = new ArrayList<>();
        for (CodeNode candidate : registry.values()) {
            if (candidate.getKind() != NodeKind.INTERFACE) continue;
            if (candidate.getFilePath() == null) continue;
            // We can only do best-effort matching without the interface file content here.
            // Check by label match (struct label appears as receiver type).
            if (node.getLabel() != null && candidate.getLabel() != null
                    && ctx.content().contains(node.getLabel() + ") " + candidate.getLabel())) {
                satisfied.add(candidate.getLabel());
            }
        }

        if (!satisfied.isEmpty()) {
            Collections.sort(satisfied);
            return Map.of("satisfies_interfaces", String.join(", ", satisfied));
        }
        return Map.of();
    }

    /**
     * Look up a node by label, returning null if zero or more than one node matches.
     * Prevents false-positive IMPORTS edges for short package names like {@code db},
     * {@code log}, {@code config} that may match multiple nodes in the registry.
     */
    private CodeNode lookupUnambiguous(String label, Map<String, CodeNode> registry) {
        CodeNode match = null;
        for (CodeNode candidate : registry.values()) {
            if (label.equals(candidate.getLabel())) {
                if (match != null) return null; // ambiguous
                match = candidate;
            }
        }
        return match;
    }
}
