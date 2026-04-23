package io.github.randomcodespace.iq.detector.typescript;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

@DetectorInfo(
    name = "typescript.express_routes",
    category = "endpoints",
    description = "Detects Express.js route definitions (app.get, router.post, etc.)",
    parser = ParserType.REGEX,
    languages = {"typescript", "javascript"},
    nodeKinds = {NodeKind.ENDPOINT},
    properties = {"framework", "http_method", "protocol"}
)
@Component
public class ExpressRouteDetector extends AbstractTypeScriptDetector {

    private static final Pattern ROUTE_PATTERN = Pattern.compile(
            "(\\w+)\\.(get|post|put|delete|patch|options|head|all)\\(\\s*['\"`]([^'\"`]+)['\"`]"
    );

    @Override
    public String getName() {
        return "typescript.express_routes";
    }

    @Override
    protected DetectorResult detectWithRegex(DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();
        String text = ctx.content();
        String filePath = ctx.filePath();
        String moduleName = ctx.moduleName();

        Matcher matcher = ROUTE_PATTERN.matcher(text);
        while (matcher.find()) {
            String routerName = matcher.group(1);
            String method = matcher.group(2).toUpperCase();
            String path = matcher.group(3);
            int line = findLineNumber(text, matcher.start());

            String nodeId = "endpoint:" + (moduleName != null ? moduleName : "") + ":" + method + ":" + path;

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
            node.getProperties().put("framework", "express");
            node.getProperties().put("router", routerName);
            nodes.add(node);
        }

        return DetectorResult.of(nodes, List.of());
    }
}
