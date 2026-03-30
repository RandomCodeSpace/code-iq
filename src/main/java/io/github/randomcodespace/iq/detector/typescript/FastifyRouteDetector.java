package io.github.randomcodespace.iq.detector.typescript;

import io.github.randomcodespace.iq.detector.AbstractAntlrDetector;
import io.github.randomcodespace.iq.grammar.AntlrParserFactory;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

@DetectorInfo(
    name = "fastify_routes",
    category = "endpoints",
    description = "Detects Fastify route definitions and plugin registrations",
    parser = ParserType.REGEX,
    languages = {"typescript", "javascript"},
    nodeKinds = {NodeKind.ENDPOINT, NodeKind.MIDDLEWARE},
    edgeKinds = {EdgeKind.IMPORTS},
    properties = {"framework", "http_method", "protocol"}
)
@Component
public class FastifyRouteDetector extends AbstractAntlrDetector {

    private static final Pattern SHORTHAND_PATTERN = Pattern.compile(
            "(\\w+)\\.(get|post|put|delete|patch)\\(\\s*['\"`]([^'\"`]+)['\"`]"
    );

    private static final Pattern ROUTE_PATTERN = Pattern.compile(
            "(\\w+)\\.route\\(\\s*\\{[\\s\\S]*?method\\s*:\\s*['\"`](\\w+)['\"`][\\s\\S]*?url\\s*:\\s*['\"`]([^'\"`]+)['\"`]",
            Pattern.DOTALL
    );

    private static final Pattern REGISTER_PATTERN = Pattern.compile(
            "(\\w+)\\.register\\(\\s*(\\w+|import\\([^)]+\\))"
    );

    private static final Pattern HOOK_PATTERN = Pattern.compile(
            "(\\w+)\\.addHook\\(\\s*['\"`](\\w+)['\"`]"
    );

    private static final Pattern SCHEMA_PATTERN = Pattern.compile(
            "schema\\s*:\\s*\\{([^}]+)\\}"
    );

    @Override
    public String getName() {
        return "fastify_routes";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("typescript", "javascript");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        // Skip ANTLR parsing — regex is the primary detection method for this detector
        // ANTLR infrastructure is in place for future enhancement
        return detectWithRegex(ctx);
    }

    @Override
    protected DetectorResult detectWithRegex(DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        String text = ctx.content();
        String filePath = ctx.filePath();
        String moduleName = ctx.moduleName();
        Set<String> seenIds = new HashSet<>();

        // Shorthand routes
        Matcher matcher = SHORTHAND_PATTERN.matcher(text);
        while (matcher.find()) {
            String method = matcher.group(2).toUpperCase();
            String path = matcher.group(3);
            int line = findLineNumber(text, matcher.start());
            String nodeId = "fastify:" + filePath + ":" + method + ":" + path + ":" + line;
            seenIds.add(nodeId);

            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.ENDPOINT);
            node.setLabel(method + " " + path);
            node.setFqn(filePath + "::" + method + ":" + path);
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.getProperties().put("protocol", "REST");
            node.getProperties().put("http_method", method);
            node.getProperties().put("path_pattern", path);
            node.getProperties().put("framework", "fastify");
            nodes.add(node);
        }

        // Route objects
        matcher = ROUTE_PATTERN.matcher(text);
        while (matcher.find()) {
            String method = matcher.group(2).toUpperCase();
            String path = matcher.group(3);
            int line = findLineNumber(text, matcher.start());
            String nodeId = "fastify:" + filePath + ":" + method + ":" + path + ":" + line;
            if (seenIds.contains(nodeId)) continue;
            seenIds.add(nodeId);

            // Check for schema
            int routeStart = matcher.start();
            int routeEnd = text.indexOf(");", routeStart);
            if (routeEnd < 0) routeEnd = text.length();
            else routeEnd += 2;
            String routeBlock = text.substring(routeStart, routeEnd);

            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.ENDPOINT);
            node.setLabel(method + " " + path);
            node.setFqn(filePath + "::" + method + ":" + path);
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.getProperties().put("protocol", "REST");
            node.getProperties().put("http_method", method);
            node.getProperties().put("path_pattern", path);
            node.getProperties().put("framework", "fastify");

            Matcher schemaMatcher = SCHEMA_PATTERN.matcher(routeBlock);
            if (schemaMatcher.find()) {
                node.getProperties().put("schema", schemaMatcher.group(1).trim());
            }
            nodes.add(node);
        }

        // Register plugins
        matcher = REGISTER_PATTERN.matcher(text);
        while (matcher.find()) {
            String pluginRef = matcher.group(2);
            int line = findLineNumber(text, matcher.start());

            String edgeSource = "fastify:" + filePath + ":server:" + line;
            String edgeTarget = "fastify:" + filePath + ":plugin:" + pluginRef + ":" + line;

            CodeEdge edge = new CodeEdge();
            edge.setId(edgeSource + "->" + edgeTarget);
            edge.setKind(EdgeKind.IMPORTS);
            edge.setSourceId(edgeSource);
            edge.getProperties().put("framework", "fastify");
            edge.getProperties().put("plugin", pluginRef);
            edges.add(edge);
        }

        // Hooks
        matcher = HOOK_PATTERN.matcher(text);
        while (matcher.find()) {
            String hookName = matcher.group(2);
            int line = findLineNumber(text, matcher.start());
            String nodeId = "fastify:" + filePath + ":hook:" + hookName + ":" + line;

            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.MIDDLEWARE);
            node.setLabel("hook:" + hookName);
            node.setFqn(filePath + "::hook:" + hookName);
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.getProperties().put("framework", "fastify");
            node.getProperties().put("hook_name", hookName);
            nodes.add(node);
        }

        return DetectorResult.of(nodes, edges);
    }
}
