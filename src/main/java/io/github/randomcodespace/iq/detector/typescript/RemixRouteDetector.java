package io.github.randomcodespace.iq.detector.typescript;

import io.github.randomcodespace.iq.detector.AbstractAntlrDetector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

@DetectorInfo(
    name = "remix_routes",
    category = "frontend",
    description = "Detects Remix route modules (loaders, actions, components)",
    parser = ParserType.REGEX,
    languages = {"typescript", "javascript"},
    nodeKinds = {NodeKind.COMPONENT, NodeKind.ENDPOINT},
    properties = {"framework", "http_method", "route_path"}
)
@Component
public class RemixRouteDetector extends AbstractAntlrDetector {
    private static final String PROP_FRAMEWORK = "framework";
    private static final String PROP_REMIX = "remix";
    private static final String PROP_ROUTE_PATH = "route_path";
    private static final String PROP_TYPE = "type";


    private static final Pattern LOADER_PATTERN = Pattern.compile(
            "export\\s+(?:async\\s+)?function\\s+loader\\s*\\("
    );

    private static final Pattern ACTION_PATTERN = Pattern.compile(
            "export\\s+(?:async\\s+)?function\\s+action\\s*\\("
    );

    private static final Pattern DEFAULT_COMPONENT_PATTERN = Pattern.compile(
            "export\\s+default\\s+function\\s+(\\w*)\\s*\\("
    );

    private static final Pattern USE_LOADER_DATA = Pattern.compile(
            "\\buseLoaderData\\s*\\(\\s*\\)"
    );

    private static final Pattern USE_ACTION_DATA = Pattern.compile(
            "\\buseActionData\\s*\\(\\s*\\)"
    );

    private static final Pattern EXTENSION_RE = Pattern.compile(
            "\\.(tsx?|jsx?)$"
    );

    @Override
    public String getName() {
        return "remix_routes";
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
        String text = ctx.content();
        String filePath = ctx.filePath();
        String moduleName = ctx.moduleName();
        String routePath = deriveRoutePath(filePath);

        // Loader exports
        Matcher matcher = LOADER_PATTERN.matcher(text);
        while (matcher.find()) {
            int line = findLineNumber(text, matcher.start());
            String nodeId = "remix:" + filePath + ":loader:" + line;
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.ENDPOINT);
            node.setLabel("loader " + (routePath != null ? routePath : filePath));
            node.setFqn(filePath + "::loader");
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.getProperties().put(PROP_FRAMEWORK, PROP_REMIX);
            node.getProperties().put(PROP_TYPE, "loader");
            node.getProperties().put("http_method", "GET");
            if (routePath != null) {
                node.getProperties().put(PROP_ROUTE_PATH, routePath);
            }
            nodes.add(node);
        }

        // Action exports
        matcher = ACTION_PATTERN.matcher(text);
        while (matcher.find()) {
            int line = findLineNumber(text, matcher.start());
            String nodeId = "remix:" + filePath + ":action:" + line;
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.ENDPOINT);
            node.setLabel("action " + (routePath != null ? routePath : filePath));
            node.setFqn(filePath + "::action");
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.getProperties().put(PROP_FRAMEWORK, PROP_REMIX);
            node.getProperties().put(PROP_TYPE, "action");
            node.getProperties().put("http_method", "POST");
            if (routePath != null) {
                node.getProperties().put(PROP_ROUTE_PATH, routePath);
            }
            nodes.add(node);
        }

        // Default component export
        boolean hasLoaderData = USE_LOADER_DATA.matcher(text).find();
        boolean hasActionData = USE_ACTION_DATA.matcher(text).find();

        matcher = DEFAULT_COMPONENT_PATTERN.matcher(text);
        while (matcher.find()) {
            String compName = matcher.group(1);
            if (compName == null || compName.isEmpty()) compName = "default";
            int line = findLineNumber(text, matcher.start());
            String nodeId = "remix:" + filePath + ":component:" + compName;
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.COMPONENT);
            node.setLabel(compName);
            node.setFqn(filePath + "::" + compName);
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.getProperties().put(PROP_FRAMEWORK, PROP_REMIX);
            node.getProperties().put(PROP_TYPE, "component");
            if (routePath != null) {
                node.getProperties().put(PROP_ROUTE_PATH, routePath);
            }
            if (hasLoaderData) {
                node.getProperties().put("uses_loader_data", true);
            }
            if (hasActionData) {
                node.getProperties().put("uses_action_data", true);
            }
            nodes.add(node);
        }

        return DetectorResult.of(nodes, List.of());
    }

    private String deriveRoutePath(String filePath) {
        if (!filePath.contains("app/routes/")) {
            return null;
        }
        String segment = filePath.split("app/routes/", 2)[1];
        segment = EXTENSION_RE.matcher(segment).replaceAll("");

        // Handle _index convention
        if ("_index".equals(segment) || segment.endsWith("/_index")) {
            String prefix = segment.substring(0, segment.lastIndexOf("_index"));
            prefix = prefix.replaceAll("[/.]$", "");
            if (prefix.isEmpty()) return "/";
            return "/" + prefix.replace(".", "/");
        }

        String[] parts = segment.split("\\.");
        List<String> pathParts = new ArrayList<>();
        for (String part : parts) {
            if (part.startsWith("$")) {
                pathParts.add(":" + part.substring(1));
            } else if (part.endsWith("_")) {
                pathParts.add(part.substring(0, part.length() - 1));
            } else {
                pathParts.add(part);
            }
        }
        return "/" + String.join("/", pathParts);
    }
}
