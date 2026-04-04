package io.github.randomcodespace.iq.detector.python;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.grammar.python.Python3Parser;
import io.github.randomcodespace.iq.grammar.python.Python3ParserBaseListener;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

@DetectorInfo(
    name = "python.fastapi_routes",
    category = "endpoints",
    description = "Detects FastAPI route definitions (@app.get, @app.post, etc.)",
    parser = ParserType.ANTLR,
    languages = {"python"},
    nodeKinds = {NodeKind.ENDPOINT},
    properties = {"framework", "http_method", "protocol"}
)
@Component
public class FastAPIRouteDetector extends AbstractPythonAntlrDetector {

    private static final Set<String> HTTP_METHODS = Set.of(
            "get", "post", "put", "delete", "patch", "options", "head"
    );

    // --- Regex fallback patterns ---
    private static final Pattern ROUTE_PATTERN = Pattern.compile(
            "@(\\w+)\\.(get|post|put|delete|patch|options|head)\\(\\s*['\"]([^'\"]+)['\"]"
            + ".*?\\)\\s*\\n(?:\\s*async\\s+)?def\\s+(\\w+)",
            Pattern.DOTALL
    );

    private static final Pattern ROUTER_PREFIX = Pattern.compile(
            "(\\w+)\\s*=\\s*APIRouter\\(.*?prefix\\s*=\\s*['\"]([^'\"]+)['\"]",
            Pattern.DOTALL
    );

    @Override
    public String getName() {
        return "python.fastapi_routes";
    }

    @Override
    protected DetectorResult detectWithAst(ParseTree tree, DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();
        String filePath = ctx.filePath();
        String moduleName = ctx.moduleName();

        // Collect router prefixes via regex (assignments like `router = APIRouter(prefix="/api")`)
        // This is simpler than walking assignments in the AST for this specific pattern
        Map<String, String> prefixes = new HashMap<>();
        Matcher prefixMatcher = ROUTER_PREFIX.matcher(ctx.content());
        while (prefixMatcher.find()) {
            prefixes.put(prefixMatcher.group(1), prefixMatcher.group(2));
        }

        ParseTreeWalker.DEFAULT.walk(new Python3ParserBaseListener() {
            @Override
            public void enterDecorated(Python3Parser.DecoratedContext decorated) {
                if (decorated.decorators() == null) return;
                // Get the function name from funcdef or async_funcdef
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
                    // Expect pattern: router.get, app.post, etc.
                    if (names.size() != 2) continue;

                    String routerName = names.get(0).getText();
                    String methodName = names.get(1).getText().toLowerCase();
                    if (!HTTP_METHODS.contains(methodName)) continue;

                    // Extract path from decorator arguments
                    String path = extractFirstStringArg(dec.arglist());
                    if (path == null) continue;

                    String prefix = prefixes.getOrDefault(routerName, "");
                    String fullPath = prefix + path;
                    String method = methodName.toUpperCase();
                    int line = lineOf(dec);

                    nodes.add(createRouteEndpoint(filePath, moduleName, line, method, fullPath, funcName, routerName));
                }
            }
        }, tree);

        return DetectorResult.of(nodes, List.of());
    }

    @Override
    protected DetectorResult detectWithRegex(DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();
        String text = ctx.content();
        if (text == null || text.isEmpty()) {
            return DetectorResult.empty();
        }
        String filePath = ctx.filePath();
        String moduleName = ctx.moduleName();

        Map<String, String> prefixes = new HashMap<>();
        Matcher prefixMatcher = ROUTER_PREFIX.matcher(text);
        while (prefixMatcher.find()) {
            prefixes.put(prefixMatcher.group(1), prefixMatcher.group(2));
        }

        Matcher routeMatcher = ROUTE_PATTERN.matcher(text);
        while (routeMatcher.find()) {
            String routerName = routeMatcher.group(1);
            String method = routeMatcher.group(2).toUpperCase();
            String path = routeMatcher.group(3);
            String funcName = routeMatcher.group(4);

            String prefix = prefixes.getOrDefault(routerName, "");
            String fullPath = prefix + path;

            int line = findLineNumber(text, routeMatcher.start());

            nodes.add(createRouteEndpoint(filePath, moduleName, line, method, fullPath, funcName, routerName));
        }

        return DetectorResult.of(nodes, List.of());
    }

    private static CodeNode createRouteEndpoint(String filePath, String moduleName, int line,
                                                String method, String fullPath, String funcName, String routerName) {
        String nodeId = "endpoint:" + (moduleName != null ? moduleName : "") + ":" + method + ":" + fullPath;
        CodeNode node = new CodeNode();
        node.setId(nodeId);
        node.setKind(NodeKind.ENDPOINT);
        node.setLabel(method + " " + fullPath);
        node.setFqn(filePath + "::" + funcName);
        node.setModule(moduleName);
        node.setFilePath(filePath);
        node.setLineStart(line);
        node.getProperties().put("protocol", "REST");
        node.getProperties().put("http_method", method);
        node.getProperties().put("path_pattern", fullPath);
        node.getProperties().put("framework", "fastapi");
        node.getProperties().put("router", routerName);
        return node;
    }

    /**
     * Extract the first string literal argument from an arglist.
     */
    private static String extractFirstStringArg(Python3Parser.ArglistContext arglist) {
        if (arglist == null) return null;
        for (var arg : arglist.argument()) {
            String argText = arg.getText();
            // Strip quotes
            if ((argText.startsWith("\"") && argText.endsWith("\""))
                    || (argText.startsWith("'") && argText.endsWith("'"))) {
                return argText.substring(1, argText.length() - 1);
            }
        }
        return null;
    }
}
