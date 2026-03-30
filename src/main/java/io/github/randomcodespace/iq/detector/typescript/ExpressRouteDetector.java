package io.github.randomcodespace.iq.detector.typescript;

import io.github.randomcodespace.iq.detector.AbstractAntlrDetector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.grammar.AntlrParserFactory;
import io.github.randomcodespace.iq.grammar.javascript.JavaScriptParser;
import io.github.randomcodespace.iq.grammar.javascript.JavaScriptParserBaseListener;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
public class ExpressRouteDetector extends AbstractAntlrDetector {

    private static final Set<String> HTTP_METHODS = Set.of(
            "get", "post", "put", "delete", "patch", "options", "head", "all"
    );

    private static final Pattern ROUTE_PATTERN = Pattern.compile(
            "(\\w+)\\.(get|post|put|delete|patch|options|head|all)\\(\\s*['\"`]([^'\"`]+)['\"`]"
    );

    @Override
    public String getName() {
        return "typescript.express_routes";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("typescript", "javascript");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        // Skip ANTLR parsing — regex is the primary detection method for this detector.
        // The JavaScript ANTLR grammar is too slow for production use (1-20s per file).
        // Regex produces identical results for Express route detection.
        return detectWithRegex(ctx);
    }

    @Override
    protected ParseTree parse(DetectorContext ctx) {
        // Not called when detect() is overridden, kept for potential future use
        return null;
    }

    @Override
    protected DetectorResult detectWithAst(ParseTree tree, DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();
        String filePath = ctx.filePath();
        String moduleName = ctx.moduleName();

        ParseTreeWalker.DEFAULT.walk(new JavaScriptParserBaseListener() {
            @Override
            public void enterArgumentsExpression(JavaScriptParser.ArgumentsExpressionContext argCtx) {
                // Look for: expr.method(args) where method is an HTTP method
                if (argCtx.singleExpression() instanceof JavaScriptParser.MemberDotExpressionContext memberCtx) {
                    String methodName = memberCtx.identifierName().getText();
                    if (!HTTP_METHODS.contains(methodName)) return;

                    String routerName = extractIdentifierText(memberCtx.singleExpression());
                    if (routerName == null) return;

                    // Get the first string argument (the path)
                    String path = extractFirstStringArg(argCtx.arguments());
                    if (path == null) return;

                    String method = methodName.toUpperCase();
                    int line = lineOf(argCtx);

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
            }
        }, tree);

        return DetectorResult.of(nodes, List.of());
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

    /** Extract a simple identifier name from a single expression, or null. */
    static String extractIdentifierText(JavaScriptParser.SingleExpressionContext expr) {
        if (expr instanceof JavaScriptParser.IdentifierExpressionContext idCtx) {
            return idCtx.getText();
        }
        // For chained access like `this.app`, return the whole text
        if (expr instanceof JavaScriptParser.MemberDotExpressionContext memberCtx) {
            return memberCtx.getText();
        }
        return expr != null ? expr.getText() : null;
    }

    /** Extract the first string literal argument from an arguments context. */
    static String extractFirstStringArg(JavaScriptParser.ArgumentsContext args) {
        if (args == null || args.argument() == null || args.argument().isEmpty()) return null;
        var firstArg = args.argument(0);
        if (firstArg == null || firstArg.singleExpression() == null) return null;
        var expr = firstArg.singleExpression();
        return extractStringLiteral(expr);
    }

    /** Extract a string literal value (strip quotes) from a single expression. */
    static String extractStringLiteral(JavaScriptParser.SingleExpressionContext expr) {
        if (expr instanceof JavaScriptParser.LiteralExpressionContext litCtx) {
            var literal = litCtx.literal();
            if (literal != null && literal.StringLiteral() != null) {
                String raw = literal.StringLiteral().getText();
                return raw.substring(1, raw.length() - 1);
            }
        }
        // Handle template strings (backtick with no expressions)
        if (expr instanceof JavaScriptParser.TemplateStringExpressionContext) {
            String raw = expr.getText();
            if (raw.startsWith("`") && raw.endsWith("`")) {
                return raw.substring(1, raw.length() - 1);
            }
        }
        return null;
    }
}
