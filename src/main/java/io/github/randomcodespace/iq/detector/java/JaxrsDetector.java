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
 * Detects JAX-RS REST endpoints from annotations.
 */
@Component
public class JaxrsDetector extends AbstractRegexDetector {

    private static final Pattern PATH_RE = Pattern.compile("@Path\\s*\\(\\s*\"([^\"]*)\"");
    private static final Pattern HTTP_METHOD_RE = Pattern.compile("@(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\\b");
    private static final Pattern PRODUCES_RE = Pattern.compile("@Produces\\s*\\(\\s*\\{?\\s*(?:MediaType\\.\\w+|\"([^\"]*)\")");
    private static final Pattern CONSUMES_RE = Pattern.compile("@Consumes\\s*\\(\\s*\\{?\\s*(?:MediaType\\.\\w+|\"([^\"]*)\")");
    private static final Pattern CLASS_RE = Pattern.compile("(?:public\\s+)?class\\s+(\\w+)");
    private static final Pattern JAVA_METHOD_RE = Pattern.compile(
            "(?:public|protected|private)?\\s*(?:static\\s+)?(?:[\\w<>\\[\\],\\s]+)\\s+(\\w+)\\s*\\(");

    @Override
    public String getName() {
        return "jaxrs";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("java");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) return DetectorResult.empty();

        if (!text.contains("@Path") && !text.contains("javax.ws.rs") && !text.contains("jakarta.ws.rs")) {
            return DetectorResult.empty();
        }

        String[] lines = text.split("\n", -1);
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        String className = null;
        String classBasePath = "";
        for (int i = 0; i < lines.length; i++) {
            Matcher cm = CLASS_RE.matcher(lines[i]);
            if (cm.find()) {
                className = cm.group(1);
                for (int j = Math.max(0, i - 5); j < i; j++) {
                    Matcher pm = PATH_RE.matcher(lines[j]);
                    if (pm.find()) {
                        classBasePath = pm.group(1).replaceAll("/+$", "");
                        break;
                    }
                }
                break;
            }
        }
        if (className == null) return DetectorResult.empty();

        String classNodeId = ctx.filePath() + ":" + className;

        for (int i = 0; i < lines.length; i++) {
            Matcher m = HTTP_METHOD_RE.matcher(lines[i]);
            if (!m.find()) continue;

            String httpMethod = m.group(1);

            boolean isClassLevel = false;
            for (int k = i + 1; k < Math.min(i + 5, lines.length); k++) {
                String stripped = lines[k].trim();
                if (stripped.startsWith("@") || stripped.isEmpty()) continue;
                if (stripped.contains("class ") || stripped.contains("interface ")) isClassLevel = true;
                break;
            }
            if (isClassLevel) continue;

            String methodPath = null;
            for (int k = Math.max(0, i - 3); k < Math.min(i + 4, lines.length); k++) {
                if (k == i) continue;
                Matcher pm = PATH_RE.matcher(lines[k]);
                if (pm.find()) { methodPath = pm.group(1); break; }
            }

            String fullPath;
            if (methodPath != null) {
                fullPath = classBasePath + "/" + methodPath.replaceAll("^/+", "");
            } else {
                fullPath = classBasePath.isEmpty() ? "/" : classBasePath;
            }
            if (!fullPath.startsWith("/")) fullPath = "/" + fullPath;

            String produces = null, consumes = null;
            for (int k = Math.max(0, i - 5); k < Math.min(i + 5, lines.length); k++) {
                if (produces == null) {
                    Matcher pm = PRODUCES_RE.matcher(lines[k]);
                    if (pm.find()) produces = pm.group(1);
                }
                if (consumes == null) {
                    Matcher cm = CONSUMES_RE.matcher(lines[k]);
                    if (cm.find()) consumes = cm.group(1);
                }
            }

            String methodName = null;
            for (int k = i + 1; k < Math.min(i + 5, lines.length); k++) {
                Matcher mm = JAVA_METHOD_RE.matcher(lines[k]);
                if (mm.find()) { methodName = mm.group(1); break; }
            }

            String endpointLabel = httpMethod + " " + fullPath;
            String endpointId = ctx.filePath() + ":" + className + ":" + (methodName != null ? methodName : "unknown")
                    + ":" + httpMethod + ":" + fullPath;

            CodeNode node = new CodeNode();
            node.setId(endpointId);
            node.setKind(NodeKind.ENDPOINT);
            node.setLabel(endpointLabel);
            node.setFqn(methodName != null ? className + "." + methodName : className);
            node.setFilePath(ctx.filePath());
            node.setLineStart(i + 1);
            node.getAnnotations().add("@" + httpMethod);
            node.getProperties().put("http_method", httpMethod);
            node.getProperties().put("path", fullPath);
            if (produces != null) node.getProperties().put("produces", produces);
            if (consumes != null) node.getProperties().put("consumes", consumes);
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
}
