package io.github.randomcodespace.iq.detector.python;

import io.github.randomcodespace.iq.detector.AbstractAntlrDetector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.grammar.AntlrParserFactory;
import io.github.randomcodespace.iq.grammar.python.Python3Parser;
import io.github.randomcodespace.iq.grammar.python.Python3ParserBaseListener;
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

@Component
public class DjangoViewDetector extends AbstractAntlrDetector {

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
    public Set<String> getSupportedLanguages() {
        return Set.of("python");
    }

    @Override
    protected ParseTree parse(DetectorContext ctx) {
        return AntlrParserFactory.parse("python", ctx.content());
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
                String pathPattern = urlMatcher.group(1);
                String viewRef = urlMatcher.group(2);
                int line = findLineNumber(text, urlMatcher.start());

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
                nodes.add(node);
            }
        }

        // Detect class-based views via AST
        ParseTreeWalker.DEFAULT.walk(new Python3ParserBaseListener() {
            @Override
            public void enterClassdef(Python3Parser.ClassdefContext classCtx) {
                if (classCtx.name() == null) return;
                String className = classCtx.name().getText();

                // Check if any base class contains View, ViewSet, or Mixin
                String bases = getBaseClassesText(classCtx);
                if (bases == null || (!bases.contains("View") && !bases.contains("ViewSet") && !bases.contains("Mixin"))) {
                    return;
                }

                int line = lineOf(classCtx);
                String nodeId = "class:" + filePath + "::" + className;
                CodeNode node = new CodeNode();
                node.setId(nodeId);
                node.setKind(NodeKind.CLASS);
                node.setLabel(className);
                node.setFqn(filePath + "::" + className);
                node.setModule(moduleName);
                node.setFilePath(filePath);
                node.setLineStart(line);
                node.setAnnotations(List.of("extends:" + bases.trim()));
                node.getProperties().put("framework", "django");
                node.getProperties().put("stereotype", "view");
                nodes.add(node);
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
                String pathPattern = urlMatcher.group(1);
                String viewRef = urlMatcher.group(2);
                int line = findLineNumber(text, urlMatcher.start());

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
                nodes.add(node);
            }
        }

        Matcher cbvMatcher = CBV_PATTERN.matcher(text);
        while (cbvMatcher.find()) {
            String className = cbvMatcher.group(1);
            String bases = cbvMatcher.group(2);
            int line = findLineNumber(text, cbvMatcher.start());

            String nodeId = "class:" + filePath + "::" + className;
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.CLASS);
            node.setLabel(className);
            node.setFqn(filePath + "::" + className);
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.setAnnotations(List.of("extends:" + bases.trim()));
            node.getProperties().put("framework", "django");
            node.getProperties().put("stereotype", "view");
            nodes.add(node);
        }

        return DetectorResult.of(nodes, List.of());
    }

    /**
     * Extract base classes text from a classdef context's arglist.
     */
    private static String getBaseClassesText(Python3Parser.ClassdefContext classCtx) {
        if (classCtx.arglist() == null) return null;
        StringBuilder sb = new StringBuilder();
        for (var arg : classCtx.arglist().argument()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(arg.getText());
        }
        return sb.toString();
    }
}
