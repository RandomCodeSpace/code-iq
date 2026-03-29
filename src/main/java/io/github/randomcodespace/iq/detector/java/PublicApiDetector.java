package io.github.randomcodespace.iq.detector.java;

import io.github.randomcodespace.iq.detector.AbstractRegexDetector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects public and protected methods in Java classes and interfaces (regex port of tree-sitter detector).
 */
@Component
public class PublicApiDetector extends AbstractRegexDetector {

    private static final Pattern CLASS_RE = Pattern.compile("(?:public\\s+)?(?:abstract\\s+)?class\\s+(\\w+)");
    private static final Pattern INTERFACE_RE = Pattern.compile("(?:public\\s+)?interface\\s+(\\w+)");
    private static final Pattern METHOD_RE = Pattern.compile(
            "(public|protected)\\s+(?:static\\s+)?(?:abstract\\s+)?([\\w<>\\[\\],?\\s]+)\\s+(\\w+)\\s*\\(([^)]*)\\)");
    private static final Set<String> SKIP_METHODS = Set.of("toString", "hashCode", "equals", "clone", "finalize");

    @Override
    public String getName() {
        return "java.public_api";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("java");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) return DetectorResult.empty();

        String[] lines = text.split("\n", -1);
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        // Find the class or interface name
        String className = null;
        boolean isInterface = false;
        for (String line : lines) {
            Matcher im = INTERFACE_RE.matcher(line);
            if (im.find()) {
                className = im.group(1);
                isInterface = true;
                break;
            }
            Matcher cm = CLASS_RE.matcher(line);
            if (cm.find()) {
                className = cm.group(1);
                break;
            }
        }

        if (className == null) return DetectorResult.empty();

        String classNodeId = ctx.filePath() + ":" + className;

        for (int i = 0; i < lines.length; i++) {
            Matcher m = METHOD_RE.matcher(lines[i]);
            if (!m.find()) continue;

            String visibility = m.group(1);
            String returnType = m.group(2).trim();
            String methodName = m.group(3);
            String paramsStr = m.group(4).trim();

            if (SKIP_METHODS.contains(methodName)) continue;

            // Parse parameter types
            List<String> paramTypes = new ArrayList<>();
            if (!paramsStr.isEmpty()) {
                for (String param : paramsStr.split(",")) {
                    String trimmed = param.trim();
                    // last word is the name, everything before is the type
                    int lastSpace = trimmed.lastIndexOf(' ');
                    if (lastSpace > 0) {
                        paramTypes.add(trimmed.substring(0, lastSpace).trim());
                    }
                }
            }

            // Skip trivial getters/setters
            if (isTrivialAccessor(methodName, paramTypes.size())) continue;

            boolean isStatic = lines[i].contains("static ");
            boolean isAbstract = lines[i].contains("abstract ");

            String paramSig = String.join(",", paramTypes);
            String methodId = ctx.filePath() + ":" + className + ":" + methodName + "(" + paramSig + ")";

            CodeNode node = new CodeNode();
            node.setId(methodId);
            node.setKind(NodeKind.METHOD);
            node.setLabel(className + "." + methodName);
            node.setFqn(className + "." + methodName + "(" + paramSig + ")");
            node.setFilePath(ctx.filePath());
            node.setLineStart(i + 1);
            node.getProperties().put("visibility", visibility);
            node.getProperties().put("return_type", returnType);
            node.getProperties().put("parameters", paramTypes);
            node.getProperties().put("is_static", isStatic);
            node.getProperties().put("is_abstract", isAbstract);
            nodes.add(node);

            CodeEdge edge = new CodeEdge();
            edge.setId(classNodeId + "->defines->" + methodId);
            edge.setKind(EdgeKind.DEFINES);
            edge.setSourceId(classNodeId);
            edge.setTarget(node);
            edges.add(edge);
        }

        return DetectorResult.of(nodes, edges);
    }

    private boolean isTrivialAccessor(String name, int paramCount) {
        if (paramCount > 1) return false;
        return name.startsWith("get") || name.startsWith("set") || name.startsWith("is");
    }
}
