package io.github.randomcodespace.iq.detector.python;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.grammar.python.Python3Parser;
import io.github.randomcodespace.iq.grammar.python.Python3ParserBaseListener;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

@DetectorInfo(
    name = "python.flask_routes",
    category = "endpoints",
    description = "Detects Flask route definitions (@app.route, Blueprint routes)",
    parser = ParserType.ANTLR,
    languages = {"python"},
    nodeKinds = {NodeKind.ENDPOINT},
    edgeKinds = {EdgeKind.EXPOSES},
    properties = {"framework", "http_method", "protocol"}
)
@Component
public class FlaskRouteDetector extends AbstractPythonAntlrDetector {

    // --- Regex fallback patterns ---
    private static final Pattern ROUTE_PATTERN = Pattern.compile(
            "@(\\w+)\\.(route)\\(\\s*['\"]([^'\"]+)['\"]"
            + "(?:.*?methods\\s*=\\s*\\[([^\\]]+)\\])?"
            + ".*?\\)\\s*\\n\\s*def\\s+(\\w+)",
            Pattern.DOTALL
    );

    @Override
    public String getName() {
        return "python.flask_routes";
    }

    @Override
    protected DetectorResult detectWithAst(ParseTree tree, DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        String filePath = ctx.filePath();
        String moduleName = ctx.moduleName();

        ParseTreeWalker.DEFAULT.walk(new Python3ParserBaseListener() {
            @Override
            public void enterDecorated(Python3Parser.DecoratedContext decorated) {
                if (decorated.decorators() == null) return;
                // Get the function name
                String funcName = null;
                if (decorated.funcdef() != null && decorated.funcdef().name() != null) {
                    funcName = decorated.funcdef().name().getText();
                } else if (decorated.async_funcdef() != null
                        && decorated.async_funcdef().funcdef() != null
                        && decorated.async_funcdef().funcdef().name() != null) {
                    funcName = decorated.async_funcdef().funcdef().name().getText();
                }
                if (funcName == null) return;

                for (var dec : decorated.decorators().decorator()) {
                    if (dec.dotted_name() == null) continue;
                    var names = dec.dotted_name().name();
                    if (names.size() != 2) continue;

                    String blueprint = names.get(0).getText();
                    String methodName = names.get(1).getText();
                    if (!"route".equals(methodName)) continue;

                    // Extract path from first argument
                    String path = extractFirstStringArg(dec.arglist());
                    if (path == null) continue;

                    // Extract methods from methods=[...] keyword argument
                    List<String> methods = extractMethodsArg(dec.arglist());
                    if (methods.isEmpty()) {
                        methods.add("GET");
                    }

                    int line = lineOf(dec);

                    for (String method : methods) {
                        CodeNode node = createFlaskRouteEndpoint(filePath, moduleName, line, method, path, funcName, blueprint);
                        nodes.add(node);
                        edges.add(createExposesEdge(filePath, blueprint, node.getId()));
                    }
                }
            }
        }, tree);

        return DetectorResult.of(nodes, edges);
    }

    @Override
    protected DetectorResult detectWithRegex(DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        String text = ctx.content();
        if (text == null || text.isEmpty()) {
            return DetectorResult.empty();
        }
        String filePath = ctx.filePath();
        String moduleName = ctx.moduleName();

        Matcher routeMatcher = ROUTE_PATTERN.matcher(text);
        while (routeMatcher.find()) {
            String blueprint = routeMatcher.group(1);
            String path = routeMatcher.group(3);
            String methodsRaw = routeMatcher.group(4);
            String funcName = routeMatcher.group(5);

            List<String> methods = new ArrayList<>();
            if (methodsRaw != null) {
                for (String m : methodsRaw.split(",")) {
                    methods.add(m.trim().replace("'", "").replace("\"", ""));
                }
            } else {
                methods.add("GET");
            }

            int line = findLineNumber(text, routeMatcher.start());

            for (String method : methods) {
                CodeNode node = createFlaskRouteEndpoint(filePath, moduleName, line, method, path, funcName, blueprint);
                nodes.add(node);
                edges.add(createExposesEdge(filePath, blueprint, node.getId()));
            }
        }

        return DetectorResult.of(nodes, edges);
    }

    private static CodeNode createFlaskRouteEndpoint(String filePath, String moduleName, int line,
                                                    String method, String path, String funcName, String blueprint) {
        String nodeId = "endpoint:" + (moduleName != null ? moduleName : "") + ":" + method + ":" + path;
        CodeNode node = new CodeNode();
        node.setId(nodeId);
        node.setKind(NodeKind.ENDPOINT);
        node.setLabel(method + " " + path);
        node.setFqn(filePath + "::" + funcName);
        node.setModule(moduleName);
        node.setFilePath(filePath);
        node.setLineStart(line);
        node.getProperties().put("protocol", "REST");
        node.getProperties().put("http_method", method);
        node.getProperties().put("path_pattern", path);
        node.getProperties().put("framework", "flask");
        node.getProperties().put("blueprint", blueprint);
        return node;
    }

    private static CodeEdge createExposesEdge(String filePath, String blueprint, String endpointId) {
        String classId = "class:" + filePath + "::" + blueprint;
        CodeEdge edge = new CodeEdge();
        edge.setId(classId + "->exposes->" + endpointId);
        edge.setKind(EdgeKind.EXPOSES);
        edge.setSourceId(classId);
        return edge;
    }

    private static String extractFirstStringArg(Python3Parser.ArglistContext arglist) {
        if (arglist == null) return null;
        for (var arg : arglist.argument()) {
            // Skip keyword arguments (containing '=')
            if (arg.ASSIGN() != null) continue;
            String argText = arg.getText();
            if ((argText.startsWith("\"") && argText.endsWith("\""))
                    || (argText.startsWith("'") && argText.endsWith("'"))) {
                return argText.substring(1, argText.length() - 1);
            }
        }
        return null;
    }

    private static List<String> extractMethodsArg(Python3Parser.ArglistContext arglist) {
        List<String> methods = new ArrayList<>();
        if (arglist == null) return methods;
        for (var arg : arglist.argument()) {
            String argText = arg.getText();
            // Look for methods=[...] pattern
            if (argText.startsWith("methods=")) {
                // Extract content between [ and ]
                int open = argText.indexOf('[');
                int close = argText.indexOf(']');
                if (open >= 0 && close > open) {
                    String inner = argText.substring(open + 1, close);
                    for (String m : inner.split(",")) {
                        String cleaned = m.trim().replace("'", "").replace("\"", "");
                        if (!cleaned.isEmpty()) {
                            methods.add(cleaned);
                        }
                    }
                }
            }
        }
        return methods;
    }
}
