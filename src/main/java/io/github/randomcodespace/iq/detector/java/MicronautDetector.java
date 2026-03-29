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
 * Detects Micronaut-specific patterns in Java source files.
 */
@Component
public class MicronautDetector extends AbstractRegexDetector {

    private static final Pattern CONTROLLER_RE = Pattern.compile("@Controller\\s*\\(\\s*\"([^\"]*)\"");
    private static final Pattern HTTP_METHOD_RE = Pattern.compile("@(Get|Post|Put|Delete)(?!Mapping)\\s*(?:\\(\\s*\"([^\"]*)\")?\\s*\\)?");
    private static final Pattern BEAN_SCOPE_RE = Pattern.compile("@(Singleton|Prototype|Infrastructure)\\b");
    private static final Pattern CLIENT_RE = Pattern.compile("@Client\\s*\\(\\s*\"([^\"]*)\"");
    private static final Pattern INJECT_RE = Pattern.compile("@Inject\\b");
    private static final Pattern SCHEDULED_RE = Pattern.compile("@Scheduled\\s*\\(\\s*fixedRate\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern EVENT_LISTENER_RE = Pattern.compile("@EventListener\\b");
    private static final Pattern CLASS_RE = Pattern.compile("(?:public\\s+)?class\\s+(\\w+)");
    private static final Pattern JAVA_METHOD_RE = Pattern.compile(
            "(?:public|protected|private)?\\s*(?:static\\s+)?(?:[\\w<>\\[\\],\\s]+)\\s+(\\w+)\\s*\\(");

    @Override
    public String getName() {
        return "micronaut";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("java");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) return DetectorResult.empty();

        if (!text.contains("@Controller") && !text.contains("@Get") && !text.contains("@Post")
                && !text.contains("@Put") && !text.contains("@Delete")
                && !text.contains("@Singleton") && !text.contains("@Prototype") && !text.contains("@Infrastructure")
                && !text.contains("@Client") && !text.contains("@Inject")
                && !text.contains("@Scheduled") && !text.contains("@EventListener") && !text.contains("io.micronaut")) {
            return DetectorResult.empty();
        }

        String[] lines = text.split("\n", -1);
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        String className = null;
        String controllerPath = null;
        for (int i = 0; i < lines.length; i++) {
            Matcher cm = CLASS_RE.matcher(lines[i]);
            if (cm.find()) {
                className = cm.group(1);
                for (int j = Math.max(0, i - 5); j < i; j++) {
                    Matcher pm = CONTROLLER_RE.matcher(lines[j]);
                    if (pm.find()) { controllerPath = pm.group(1).replaceAll("/+$", ""); break; }
                }
                break;
            }
        }

        String classNodeId = (className != null ? ctx.filePath() + ":" + className : ctx.filePath());

        if (controllerPath != null && className != null) {
            CodeNode ctrlNode = new CodeNode();
            ctrlNode.setId("micronaut:" + ctx.filePath() + ":controller:" + className);
            ctrlNode.setKind(NodeKind.CLASS);
            ctrlNode.setLabel("@Controller(" + controllerPath + ") " + className);
            ctrlNode.setFqn(className);
            ctrlNode.setFilePath(ctx.filePath());
            ctrlNode.setLineStart(1);
            ctrlNode.getAnnotations().add("@Controller");
            ctrlNode.getProperties().put("framework", "micronaut");
            ctrlNode.getProperties().put("path", controllerPath);
            nodes.add(ctrlNode);
        }

        for (int i = 0; i < lines.length; i++) {
            int lineno = i + 1;

            // HTTP methods
            Matcher hm = HTTP_METHOD_RE.matcher(lines[i]);
            if (hm.find()) {
                String httpMethod = hm.group(1).toUpperCase();
                String methodPath = hm.group(2) != null ? hm.group(2) : "";
                String fullPath;
                if (controllerPath != null) {
                    fullPath = !methodPath.isEmpty() ? controllerPath + "/" + methodPath.replaceAll("^/+", "") : controllerPath;
                } else {
                    fullPath = !methodPath.isEmpty() ? "/" + methodPath.replaceAll("^/+", "") : "/";
                }
                if (!fullPath.startsWith("/")) fullPath = "/" + fullPath;

                String methodName = null;
                for (int k = i + 1; k < Math.min(i + 5, lines.length); k++) {
                    Matcher mm = JAVA_METHOD_RE.matcher(lines[k]);
                    if (mm.find()) { methodName = mm.group(1); break; }
                }

                String nodeId = "micronaut:" + ctx.filePath() + ":endpoint:" + httpMethod + ":" + fullPath + ":" + lineno;
                CodeNode node = new CodeNode();
                node.setId(nodeId);
                node.setKind(NodeKind.ENDPOINT);
                node.setLabel(httpMethod + " " + fullPath);
                node.setFqn(className != null && methodName != null ? className + "." + methodName : className);
                node.setFilePath(ctx.filePath());
                node.setLineStart(lineno);
                node.getAnnotations().add("@" + hm.group(1));
                node.getProperties().put("framework", "micronaut");
                node.getProperties().put("http_method", httpMethod);
                node.getProperties().put("path", fullPath);
                nodes.add(node);

                CodeEdge edge = new CodeEdge();
                edge.setId(classNodeId + "->exposes->" + nodeId);
                edge.setKind(EdgeKind.EXPOSES);
                edge.setSourceId(classNodeId);
                edge.setTarget(node);
                edges.add(edge);
            }

            // Bean scopes
            Matcher bm = BEAN_SCOPE_RE.matcher(lines[i]);
            if (bm.find()) {
                String scope = bm.group(1);
                String nodeId = "micronaut:" + ctx.filePath() + ":scope_" + scope.toLowerCase() + ":" + lineno;
                CodeNode node = new CodeNode();
                node.setId(nodeId);
                node.setKind(NodeKind.MIDDLEWARE);
                node.setLabel("@" + scope + " (bean scope)");
                node.setFqn(className != null ? className + "." + scope : scope);
                node.setFilePath(ctx.filePath());
                node.setLineStart(lineno);
                node.getAnnotations().add("@" + scope);
                node.getProperties().put("framework", "micronaut");
                node.getProperties().put("bean_scope", scope);
                nodes.add(node);
            }

            // @Client
            Matcher clm = CLIENT_RE.matcher(lines[i]);
            if (clm.find()) {
                String clientTarget = clm.group(1);
                String nodeId = "micronaut:" + ctx.filePath() + ":client:" + lineno;
                CodeNode node = new CodeNode();
                node.setId(nodeId);
                node.setKind(NodeKind.CLASS);
                node.setLabel("@Client(" + clientTarget + ")");
                node.setFqn(clientTarget);
                node.setFilePath(ctx.filePath());
                node.setLineStart(lineno);
                node.getAnnotations().add("@Client");
                node.getProperties().put("framework", "micronaut");
                node.getProperties().put("client_target", clientTarget);
                nodes.add(node);

                CodeEdge edge = new CodeEdge();
                edge.setId(classNodeId + "->depends_on->" + nodeId);
                edge.setKind(EdgeKind.DEPENDS_ON);
                edge.setSourceId(classNodeId);
                edge.setTarget(node);
                edges.add(edge);
            }

            // @Inject
            if (INJECT_RE.matcher(lines[i]).find()) {
                String nodeId = "micronaut:" + ctx.filePath() + ":inject:" + lineno;
                CodeNode node = new CodeNode();
                node.setId(nodeId);
                node.setKind(NodeKind.MIDDLEWARE);
                node.setLabel("@Inject");
                node.setFqn(className != null ? className + ".inject" : "inject");
                node.setFilePath(ctx.filePath());
                node.setLineStart(lineno);
                node.getAnnotations().add("@Inject");
                node.getProperties().put("framework", "micronaut");
                nodes.add(node);
            }

            // @Scheduled
            Matcher sm = SCHEDULED_RE.matcher(lines[i]);
            if (sm.find()) {
                String rate = sm.group(1);
                String nodeId = "micronaut:" + ctx.filePath() + ":scheduled:" + lineno;
                CodeNode node = new CodeNode();
                node.setId(nodeId);
                node.setKind(NodeKind.EVENT);
                node.setLabel("@Scheduled(fixedRate=" + rate + ")");
                node.setFqn(className != null ? className + ".scheduled" : "scheduled");
                node.setFilePath(ctx.filePath());
                node.setLineStart(lineno);
                node.getAnnotations().add("@Scheduled");
                node.getProperties().put("framework", "micronaut");
                node.getProperties().put("fixed_rate", rate);
                nodes.add(node);
            }

            // @EventListener
            if (EVENT_LISTENER_RE.matcher(lines[i]).find()) {
                String nodeId = "micronaut:" + ctx.filePath() + ":event_listener:" + lineno;
                CodeNode node = new CodeNode();
                node.setId(nodeId);
                node.setKind(NodeKind.EVENT);
                node.setLabel("@EventListener");
                node.setFqn(className != null ? className + ".eventListener" : "eventListener");
                node.setFilePath(ctx.filePath());
                node.setLineStart(lineno);
                node.getAnnotations().add("@EventListener");
                node.getProperties().put("framework", "micronaut");
                nodes.add(node);
            }
        }

        return DetectorResult.of(nodes, edges);
    }
}
