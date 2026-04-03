package io.github.randomcodespace.iq.intelligence.extractor.typescript;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.intelligence.CapabilityLevel;
import io.github.randomcodespace.iq.intelligence.extractor.LanguageExtractionResult;
import io.github.randomcodespace.iq.intelligence.extractor.LanguageExtractor;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TypeScript/JavaScript language-specific extractor.
 *
 * <p>Also handles JavaScript nodes via the "javascript" → "typescript" alias in
 * {@link io.github.randomcodespace.iq.intelligence.extractor.LanguageEnricher}.
 *
 * <p>Capabilities:
 * <ul>
 *   <li>Import-to-symbol resolution: maps TS {@code import { X }} to source {@code CodeNode} for X.</li>
 *   <li>JSDoc type enrichment: surfaces {@code @param} / {@code @returns} from {@code lex_comment}
 *       into node properties.</li>
 * </ul>
 *
 * <p>Confidence: PARTIAL.
 */
@Component
public class TypeScriptLanguageExtractor implements LanguageExtractor {

    /** Named import: {@code import { Foo, Bar } from './path'} */
    private static final Pattern NAMED_IMPORT =
            Pattern.compile("import\\s+\\{([^}]+)\\}\\s+from\\s+['\"]([^'\"]+)['\"]");

    /** Default import: {@code import Foo from './path'} */
    private static final Pattern DEFAULT_IMPORT =
            Pattern.compile("import\\s+(\\w+)\\s+from\\s+['\"]([^'\"]+)['\"]");

    /** JSDoc @param tag: {@code @param {type} name} or {@code @param name - desc} */
    private static final Pattern JSDOC_PARAM =
            Pattern.compile("@param\\s+(?:\\{([^}]+)\\}\\s+)?(\\w+)");

    /** JSDoc @returns tag: {@code @returns {type}} */
    private static final Pattern JSDOC_RETURNS =
            Pattern.compile("@returns?\\s+\\{([^}]+)\\}");

    @Override
    public String getLanguage() {
        return "typescript";
    }

    @Override
    @SuppressWarnings("unchecked")
    public LanguageExtractionResult extract(DetectorContext ctx, CodeNode node) {
        Map<String, CodeNode> nodeRegistry = (ctx.parsedData() instanceof Map<?, ?> raw)
                ? (Map<String, CodeNode>) raw
                : Map.of();

        List<CodeEdge> symbolRefs = extractImportEdges(ctx, node, nodeRegistry);
        Map<String, String> typeHints = extractJsDocHints(node);

        return new LanguageExtractionResult(List.of(), symbolRefs, typeHints, CapabilityLevel.PARTIAL);
    }

    private List<CodeEdge> extractImportEdges(DetectorContext ctx, CodeNode node,
                                              Map<String, CodeNode> registry) {
        if (ctx.content() == null || registry.isEmpty()) return List.of();

        List<CodeEdge> edges = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        Matcher named = NAMED_IMPORT.matcher(ctx.content());
        while (named.find()) {
            String symbols = named.group(1);
            for (String symbol : symbols.split(",")) {
                String sym = symbol.trim();
                if (sym.isEmpty()) continue;
                // Strip alias: "Foo as Bar" → "Foo"
                int asIdx = sym.indexOf(" as ");
                if (asIdx >= 0) sym = sym.substring(0, asIdx).trim();
                CodeNode target = registry.get(sym);
                if (target != null && !target.getId().equals(node.getId())) {
                    String edgeId = "imports:%s:%s".formatted(node.getId(), target.getId());
                    if (seen.add(edgeId)) {
                        CodeEdge edge = new CodeEdge(edgeId, EdgeKind.IMPORTS, node.getId(), target);
                        edge.getProperties().put("confidence", "PARTIAL");
                        edge.getProperties().put("extractorName", "typescript_language_extractor");
                        edges.add(edge);
                    }
                }
            }
        }

        Matcher def = DEFAULT_IMPORT.matcher(ctx.content());
        while (def.find()) {
            String sym = def.group(1);
            CodeNode target = registry.get(sym);
            if (target != null && !target.getId().equals(node.getId())) {
                String edgeId = "imports:%s:%s".formatted(node.getId(), target.getId());
                if (seen.add(edgeId)) {
                    CodeEdge edge = new CodeEdge(edgeId, EdgeKind.IMPORTS, node.getId(), target);
                    edge.getProperties().put("confidence", "PARTIAL");
                    edge.getProperties().put("extractorName", "typescript_language_extractor");
                    edges.add(edge);
                }
            }
        }

        return edges;
    }

    private Map<String, String> extractJsDocHints(CodeNode node) {
        Object lexComment = node.getProperties().get("lex_comment");
        if (!(lexComment instanceof String comment) || comment.isBlank()) return Map.of();

        Map<String, String> hints = new LinkedHashMap<>();
        List<String> params = new ArrayList<>();

        Matcher paramMatcher = JSDOC_PARAM.matcher(comment);
        while (paramMatcher.find()) {
            String type = paramMatcher.group(1);
            String name = paramMatcher.group(2);
            params.add(type != null ? name + ":" + type : name);
        }
        if (!params.isEmpty()) {
            hints.put("jsdoc_params", String.join(", ", params));
        }

        Matcher retMatcher = JSDOC_RETURNS.matcher(comment);
        if (retMatcher.find()) {
            hints.put("jsdoc_return_type", retMatcher.group(1));
        }

        return hints;
    }
}
