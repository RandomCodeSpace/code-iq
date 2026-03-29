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
 * Detects Spring REST endpoints from mapping annotations.
 */
@Component
public class SpringRestDetector extends AbstractRegexDetector {

    private static final Pattern MAPPING_RE = Pattern.compile(
            "@(RequestMapping|GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)"
                    + "\\s*(?:\\(([^)]*)\\))?"
    );
    private static final Pattern CLASS_RE = Pattern.compile("(?:public\\s+)?class\\s+(\\w+)");
    private static final Pattern VALUE_RE = Pattern.compile("(?:value\\s*=\\s*|path\\s*=\\s*)?\\{?\\s*\"([^\"]*)\"");
    private static final Pattern METHOD_ATTR_RE = Pattern.compile("method\\s*=\\s*RequestMethod\\.(\\w+)");
    private static final Pattern PRODUCES_RE = Pattern.compile("produces\\s*=\\s*\\{?\\s*\"([^\"]*)\"");
    private static final Pattern CONSUMES_RE = Pattern.compile("consumes\\s*=\\s*\\{?\\s*\"([^\"]*)\"");
    private static final Pattern JAVA_METHOD_RE = Pattern.compile(
            "(?:public|protected|private)?\\s*(?:static\\s+)?(?:[\\w<>\\[\\],\\s]+)\\s+(\\w+)\\s*\\("
    );

    private static final Map<String, String> MAPPING_ANNOTATIONS = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH"
    );

    @Override
    public String getName() {
        return "spring_rest";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("java");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) {
            return DetectorResult.empty();
        }

        String[] lines = text.split("\n", -1);
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        // Find class name
        String className = null;
        String classBasePath = "";
        for (int i = 0; i < lines.length; i++) {
            Matcher cm = CLASS_RE.matcher(lines[i]);
            if (cm.find()) {
                className = cm.group(1);
                // Look backwards for class-level @RequestMapping
                for (int j = Math.max(0, i - 5); j < i; j++) {
                    Matcher mm = MAPPING_RE.matcher(lines[j]);
                    if (mm.find() && "RequestMapping".equals(mm.group(1))) {
                        String path = extractAttr(mm.group(2), VALUE_RE);
                        if (path != null) {
                            classBasePath = path.replaceAll("/+$", "");
                        }
                    }
                }
                break;
            }
        }

        if (className == null) {
            return DetectorResult.empty();
        }

        String classNodeId = ctx.filePath() + ":" + className;

        // Scan for method-level mapping annotations
        for (int i = 0; i < lines.length; i++) {
            Matcher m = MAPPING_RE.matcher(lines[i]);
            if (!m.find()) {
                continue;
            }

            String annotationName = m.group(1);
            String attrStr = m.group(2);

            // Skip class-level annotations
            boolean isClassLevel = false;
            for (int k = i + 1; k < Math.min(i + 5, lines.length); k++) {
                String stripped = lines[k].trim();
                if (stripped.startsWith("@") || stripped.isEmpty()) {
                    continue;
                }
                if (stripped.contains("class ") || stripped.contains("interface ")) {
                    isClassLevel = true;
                }
                break;
            }
            if (isClassLevel) {
                continue;
            }

            // Determine HTTP method
            String httpMethod = MAPPING_ANNOTATIONS.get(annotationName);
            if (httpMethod == null) {
                String extracted = extractAttr(attrStr, METHOD_ATTR_RE);
                httpMethod = extracted != null ? extracted : "GET";
            }

            // Extract path
            String path = extractAttr(attrStr, VALUE_RE);
            if (path == null && attrStr != null) {
                Matcher bare = Pattern.compile("\"([^\"]*)\"").matcher(attrStr);
                if (bare.find()) {
                    path = bare.group(1);
                }
            }
            if (path == null) {
                path = "";
            }

            String fullPath;
            if (!path.isEmpty()) {
                fullPath = classBasePath + "/" + path.replaceAll("^/+", "");
            } else {
                fullPath = classBasePath.isEmpty() ? "/" : classBasePath;
            }
            if (!fullPath.startsWith("/")) {
                fullPath = "/" + fullPath;
            }

            // Extract produces/consumes
            String produces = extractAttr(attrStr, PRODUCES_RE);
            String consumes = extractAttr(attrStr, CONSUMES_RE);

            // Find method name
            String methodName = null;
            for (int k = i + 1; k < Math.min(i + 5, lines.length); k++) {
                Matcher mm = JAVA_METHOD_RE.matcher(lines[k]);
                if (mm.find()) {
                    methodName = mm.group(1);
                    break;
                }
            }

            String endpointLabel = httpMethod + " " + fullPath;
            String endpointId = ctx.filePath() + ":" + className + ":" + (methodName != null ? methodName : "unknown") + ":" + httpMethod + ":" + fullPath;

            CodeNode node = new CodeNode();
            node.setId(endpointId);
            node.setKind(NodeKind.ENDPOINT);
            node.setLabel(endpointLabel);
            node.setFqn(methodName != null ? className + "." + methodName : className);
            node.setFilePath(ctx.filePath());
            node.setLineStart(i + 1);
            node.getAnnotations().add("@" + annotationName);
            node.getProperties().put("http_method", httpMethod);
            node.getProperties().put("path", fullPath);
            if (produces != null) {
                node.getProperties().put("produces", produces);
            }
            if (consumes != null) {
                node.getProperties().put("consumes", consumes);
            }
            nodes.add(node);

            CodeEdge edge = new CodeEdge();
            edge.setId(classNodeId + "->exposes->" + endpointId);
            edge.setKind(EdgeKind.EXPOSES);
            edge.setSourceId(classNodeId);
            edge.setTarget(node);
            edges.add(edge);
        }

        return DetectorResult.of(nodes, edges);
    }

    private static String extractAttr(String attrStr, Pattern pattern) {
        if (attrStr == null) {
            return null;
        }
        Matcher m = pattern.matcher(attrStr);
        return m.find() ? m.group(1) : null;
    }
}
