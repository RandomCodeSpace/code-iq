package io.github.randomcodespace.iq.intelligence.extractor.python;

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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Python language-specific extractor.
 *
 * <p>Capabilities:
 * <ul>
 *   <li>Import resolution: maps {@code from module import X} to {@code CodeNode} in registry.</li>
 *   <li>Type hint extraction: surfaces {@code def fn(x: int) -> str} parameter and return types
 *       from source content into node properties.</li>
 * </ul>
 *
 * <p>Confidence: PARTIAL.
 */
@Component
public class PythonLanguageExtractor implements LanguageExtractor {

    /** {@code from module import X, Y} — captures only to end of line */
    private static final Pattern FROM_IMPORT =
            Pattern.compile("from\\s+[\\w.]+\\s+import\\s+([^\\n\\r]+)");

    /** {@code import X} or {@code import X as Y} */
    private static final Pattern PLAIN_IMPORT =
            Pattern.compile("^import\\s+(\\w+)(?:\\s+as\\s+\\w+)?", Pattern.MULTILINE);

    /** {@code def fn(param: Type, ...) -> ReturnType:} */
    private static final Pattern DEF_SIGNATURE =
            Pattern.compile("def\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:->\\s*([\\w\\[\\], |]+))?\\s*:");

    /** Type-annotated parameter: {@code name: Type} */
    private static final Pattern ANNOTATED_PARAM =
            Pattern.compile("(\\w+)\\s*:\\s*([\\w\\[\\]|. ]+)");

    @Override
    public String getLanguage() {
        return "python";
    }

    @Override
    @SuppressWarnings("unchecked")
    public LanguageExtractionResult extract(DetectorContext ctx, CodeNode node) {
        Map<String, CodeNode> nodeRegistry = (ctx.parsedData() instanceof Map<?, ?> raw)
                ? (Map<String, CodeNode>) raw
                : Map.of();

        List<CodeEdge> symbolRefs = extractImportEdges(ctx, node, nodeRegistry);
        Map<String, String> typeHints = extractTypeHints(ctx, node);

        return new LanguageExtractionResult(List.of(), symbolRefs, typeHints, CapabilityLevel.PARTIAL);
    }

    private List<CodeEdge> extractImportEdges(DetectorContext ctx, CodeNode node,
                                              Map<String, CodeNode> registry) {
        if (ctx.content() == null || registry.isEmpty()) return List.of();

        List<CodeEdge> edges = new ArrayList<>();

        Matcher from = FROM_IMPORT.matcher(ctx.content());
        while (from.find()) {
            for (String symbol : from.group(1).split(",")) {
                String sym = symbol.trim();
                if (sym.isEmpty()) continue;
                CodeNode target = registry.get(sym);
                if (target != null && !target.getId().equals(node.getId())) {
                    String edgeId = "imports:%s:%s".formatted(node.getId(), target.getId());
                    edges.add(new CodeEdge(edgeId, EdgeKind.IMPORTS, node.getId(), target));
                }
            }
        }

        Matcher plain = PLAIN_IMPORT.matcher(ctx.content());
        while (plain.find()) {
            String sym = plain.group(1);
            CodeNode target = registry.get(sym);
            if (target != null && !target.getId().equals(node.getId())) {
                String edgeId = "imports:%s:%s".formatted(node.getId(), target.getId());
                edges.add(new CodeEdge(edgeId, EdgeKind.IMPORTS, node.getId(), target));
            }
        }

        return edges;
    }

    private Map<String, String> extractTypeHints(DetectorContext ctx, CodeNode node) {
        if (ctx.content() == null || node.getLabel() == null) return Map.of();

        Map<String, String> hints = new LinkedHashMap<>();
        Matcher def = DEF_SIGNATURE.matcher(ctx.content());

        while (def.find()) {
            String fnName = def.group(1);
            if (!fnName.equals(node.getLabel())) continue;

            String params = def.group(2);
            String returnType = def.group(3);

            if (params != null && !params.isBlank()) {
                List<String> annotated = new ArrayList<>();
                Matcher paramMatcher = ANNOTATED_PARAM.matcher(params);
                while (paramMatcher.find()) {
                    String paramName = paramMatcher.group(1);
                    String paramType = paramMatcher.group(2).trim();
                    if (!"self".equals(paramName) && !"cls".equals(paramName)) {
                        annotated.add(paramName + ":" + paramType);
                    }
                }
                if (!annotated.isEmpty()) {
                    hints.put("param_types", String.join(", ", annotated));
                }
            }

            if (returnType != null && !returnType.isBlank()) {
                hints.put("return_type", returnType.trim());
            }
            break; // first matching function definition is sufficient
        }

        return hints;
    }
}
