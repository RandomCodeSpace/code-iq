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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

@DetectorInfo(
    name = "python.django_views",
    category = "endpoints",
    description = "Detects Django views (function-based and class-based views)",
    parser = ParserType.ANTLR,
    languages = {"python"},
    nodeKinds = {NodeKind.CLASS, NodeKind.ENDPOINT},
    properties = {"framework", "protocol"}
)
@Component
public class DjangoViewDetector extends AbstractPythonAntlrDetector {

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?:path|re_path|url)\\(\\s*['\"]([^'\"]+)['\"]\\s*,\\s*(\\w[\\w.]*)"
    );
    private static final Pattern CBV_PATTERN = Pattern.compile(
            "class\\s+(\\w+)\\(([^)]*(?:View|ViewSet|Mixin)[^)]*)\\):"
    );

    @Override
    public String getName() {
        return "python.django_views";
    }

    @Override
    protected DetectorResult detectWithAst(ParseTree tree, DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();
        String filePath = ctx.filePath();
        String moduleName = ctx.moduleName();
        String text = ctx.content();

        // Detect URL patterns via regex within AST context (these are function calls, not class defs)
        if (text.contains("urlpatterns")) {
            Matcher urlMatcher = URL_PATTERN.matcher(text);
            while (urlMatcher.find()) {
                nodes.add(createUrlPatternEndpoint(filePath, moduleName,
                        findLineNumber(text, urlMatcher.start()), urlMatcher.group(1), urlMatcher.group(2)));
            }
        }

        // Detect class-based views via AST
        ParseTreeWalker.DEFAULT.walk(new Python3ParserBaseListener() {
            @Override
            public void enterClassdef(Python3Parser.ClassdefContext classCtx) {
                if (classCtx.name() == null) return;
                String className = classCtx.name().getText();

                String bases = getBaseClassesText(classCtx);
                if (bases == null || (!bases.contains("View") && !bases.contains("ViewSet") && !bases.contains("Mixin"))) {
                    return;
                }

                nodes.add(createCbvNode(filePath, moduleName, lineOf(classCtx), className, bases.trim()));
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

        if (text.contains("urlpatterns")) {
            Matcher urlMatcher = URL_PATTERN.matcher(text);
            while (urlMatcher.find()) {
                nodes.add(createUrlPatternEndpoint(filePath, moduleName,
                        findLineNumber(text, urlMatcher.start()), urlMatcher.group(1), urlMatcher.group(2)));
            }
        }

        Matcher cbvMatcher = CBV_PATTERN.matcher(text);
        while (cbvMatcher.find()) {
            nodes.add(createCbvNode(filePath, moduleName,
                    findLineNumber(text, cbvMatcher.start()), cbvMatcher.group(1), cbvMatcher.group(2).trim()));
        }

        return DetectorResult.of(nodes, List.of());
    }

    private static CodeNode createUrlPatternEndpoint(String filePath, String moduleName, int line,
                                                     String pathPattern, String viewRef) {
        String nodeId = "endpoint:" + (moduleName != null ? moduleName : "") + ":ALL:" + pathPattern;
        CodeNode node = new CodeNode();
        node.setId(nodeId);
        node.setKind(NodeKind.ENDPOINT);
        node.setLabel(pathPattern);
        node.setFqn(viewRef);
        node.setModule(moduleName);
        node.setFilePath(filePath);
        node.setLineStart(line);
        node.getProperties().put("protocol", "REST");
        node.getProperties().put("path_pattern", pathPattern);
        node.getProperties().put("framework", "django");
        node.getProperties().put("view_reference", viewRef);
        return node;
    }

    private static CodeNode createCbvNode(String filePath, String moduleName, int line,
                                          String className, String bases) {
        String nodeId = "class:" + filePath + "::" + className;
        CodeNode node = new CodeNode();
        node.setId(nodeId);
        node.setKind(NodeKind.CLASS);
        node.setLabel(className);
        node.setFqn(filePath + "::" + className);
        node.setModule(moduleName);
        node.setFilePath(filePath);
        node.setLineStart(line);
        node.setAnnotations(List.of("extends:" + bases));
        node.getProperties().put("framework", "django");
        node.getProperties().put("stereotype", "view");
        return node;
    }

}
