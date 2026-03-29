package io.github.randomcodespace.iq.detector.python;

import io.github.randomcodespace.iq.detector.AbstractAntlrDetector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.grammar.AntlrParserFactory;
import io.github.randomcodespace.iq.grammar.python.Python3Parser;
import io.github.randomcodespace.iq.grammar.python.Python3ParserBaseListener;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.antlr.v4.runtime.ParserRuleContext;
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

@Component
public class PythonStructuresDetector extends AbstractAntlrDetector {

    // --- Regex patterns (for fallback) ---
    private static final Pattern CLASS_RE = Pattern.compile(
            "^class\\s+(\\w+)(?:\\(([^)]*)\\))?:", Pattern.MULTILINE
    );
    private static final Pattern FUNC_RE = Pattern.compile(
            "^([^\\S\\n]*)(async\\s+)?def\\s+(\\w+)\\s*\\(", Pattern.MULTILINE
    );
    private static final Pattern IMPORT_RE = Pattern.compile(
            "^(?:from\\s+([\\w.]+)\\s+)?import\\s+([\\w., ]+)", Pattern.MULTILINE
    );
    private static final Pattern DECORATOR_RE = Pattern.compile(
            "^([^\\S\\n]*)@(\\w[\\w.]*)", Pattern.MULTILINE
    );
    private static final Pattern ALL_RE = Pattern.compile(
            "__all__\\s*=\\s*\\[([^\\]]*)\\]", Pattern.DOTALL
    );
    private static final Pattern QUOTED_NAME_RE = Pattern.compile("['\"]((\\w+))['\"]");

    @Override
    public String getName() {
        return "python_structures";
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
        List<CodeEdge> edges = new ArrayList<>();
        String text = ctx.content();
        String fp = ctx.filePath();
        String moduleName = ctx.moduleName();

        // Extract __all__ exports via regex (simpler than walking assignment expressions)
        Matcher allMatch = ALL_RE.matcher(text);
        List<String> allExports = null;
        int allMatchStart = -1;
        if (allMatch.find()) {
            allMatchStart = allMatch.start();
            String raw = allMatch.group(1);
            allExports = new ArrayList<>();
            Matcher qm = QUOTED_NAME_RE.matcher(raw);
            while (qm.find()) {
                allExports.add(qm.group(1));
            }
        }

        // Collect decorators by looking at the text (for function/class decorator collection)
        Map<Integer, List<String>> decoratorMap = collectDecorators(text);
        List<String> allExportsFinal = allExports;
        int allMatchStartFinal = allMatchStart;

        // Track classes for enclosing-class detection
        List<String> classNames = new ArrayList<>();
        List<int[]> classRanges = new ArrayList<>(); // [nameIdx, line]

        ParseTreeWalker.DEFAULT.walk(new Python3ParserBaseListener() {
            @Override
            public void enterClassdef(Python3Parser.ClassdefContext classCtx) {
                if (classCtx.name() == null) return;
                String className = classCtx.name().getText();
                int line = lineOf(classCtx);

                classNames.add(className);
                classRanges.add(new int[]{classNames.size() - 1, line});

                List<String> annotations = findDecoratorsForLine(decoratorMap, line);

                Map<String, Object> properties = new HashMap<>();
                String basesStr = getBaseClassesText(classCtx);
                if (basesStr != null && !basesStr.isBlank()) {
                    List<String> bases = new ArrayList<>();
                    for (String b : basesStr.split(",")) {
                        String trimmed = b.trim();
                        if (!trimmed.isEmpty()) {
                            bases.add(trimmed);
                        }
                    }
                    properties.put("bases", bases);
                }
                if (allExportsFinal != null && allExportsFinal.contains(className)) {
                    properties.put("exported", true);
                }

                String nodeId = "py:" + fp + ":class:" + className;
                CodeNode node = new CodeNode();
                node.setId(nodeId);
                node.setKind(NodeKind.CLASS);
                node.setLabel(className);
                node.setFqn(className);
                node.setModule(moduleName);
                node.setFilePath(fp);
                node.setLineStart(line);
                node.setAnnotations(annotations);
                node.setProperties(properties);
                nodes.add(node);

                // EXTENDS edges
                if (basesStr != null && !basesStr.isBlank()) {
                    for (String b : basesStr.split(",")) {
                        String base = b.trim();
                        if (!base.isEmpty()) {
                            CodeEdge edge = new CodeEdge();
                            edge.setId(nodeId + "->extends->" + base);
                            edge.setKind(EdgeKind.EXTENDS);
                            edge.setSourceId(nodeId);
                            edges.add(edge);
                        }
                    }
                }
            }

            @Override
            public void enterFuncdef(Python3Parser.FuncdefContext funcCtx) {
                if (funcCtx.name() == null) return;
                String funcName = funcCtx.name().getText();
                int line = lineOf(funcCtx);

                // Determine if async
                boolean isAsync = isAsyncFunction(funcCtx);

                // Determine indentation level from source
                int indent = getIndent(text, funcCtx);

                List<String> annotations = findDecoratorsForLine(decoratorMap, line);

                Map<String, Object> properties = new HashMap<>();
                if (isAsync) {
                    properties.put("async", true);
                }
                if (allExportsFinal != null && allExportsFinal.contains(funcName)) {
                    properties.put("exported", true);
                }

                if (indent == 0) {
                    // Top-level function
                    String nodeId = "py:" + fp + ":func:" + funcName;
                    CodeNode node = new CodeNode();
                    node.setId(nodeId);
                    node.setKind(NodeKind.METHOD);
                    node.setLabel(funcName);
                    node.setFqn(funcName);
                    node.setModule(moduleName);
                    node.setFilePath(fp);
                    node.setLineStart(line);
                    node.setAnnotations(annotations);
                    node.setProperties(properties);
                    nodes.add(node);
                } else {
                    String enclosingClass = findEnclosingClass(classNames, classRanges, line);
                    if (enclosingClass != null) {
                        String nodeId = "py:" + fp + ":class:" + enclosingClass + ":method:" + funcName;
                        properties.put("class", enclosingClass);
                        CodeNode node = new CodeNode();
                        node.setId(nodeId);
                        node.setKind(NodeKind.METHOD);
                        node.setLabel(enclosingClass + "." + funcName);
                        node.setFqn(enclosingClass + "." + funcName);
                        node.setModule(moduleName);
                        node.setFilePath(fp);
                        node.setLineStart(line);
                        node.setAnnotations(annotations);
                        node.setProperties(properties);
                        nodes.add(node);

                        String classNodeId = "py:" + fp + ":class:" + enclosingClass;
                        CodeEdge edge = new CodeEdge();
                        edge.setId(classNodeId + "->defines->" + nodeId);
                        edge.setKind(EdgeKind.DEFINES);
                        edge.setSourceId(classNodeId);
                        edges.add(edge);
                    }
                }
            }

            @Override
            public void enterImport_name(Python3Parser.Import_nameContext importCtx) {
                // import sys, json
                if (importCtx.dotted_as_names() != null) {
                    for (var dan : importCtx.dotted_as_names().dotted_as_name()) {
                        String importedName = dan.dotted_name().getText();
                        CodeEdge edge = new CodeEdge();
                        edge.setId(fp + "->imports->" + importedName);
                        edge.setKind(EdgeKind.IMPORTS);
                        edge.setSourceId(fp);
                        edges.add(edge);
                    }
                }
            }

            @Override
            public void enterImport_from(Python3Parser.Import_fromContext importCtx) {
                // from os.path import join
                // Extract the module name from dotted_name or dots
                StringBuilder fromModule = new StringBuilder();
                // Count dots
                if (importCtx.DOT() != null) {
                    for (var dot : importCtx.DOT()) {
                        fromModule.append(".");
                    }
                }
                if (importCtx.ELLIPSIS() != null) {
                    for (var ellipsis : importCtx.ELLIPSIS()) {
                        fromModule.append("...");
                    }
                }
                if (importCtx.dotted_name() != null) {
                    fromModule.append(importCtx.dotted_name().getText());
                }
                if (fromModule.length() > 0) {
                    CodeEdge edge = new CodeEdge();
                    edge.setId(fp + "->imports->" + fromModule);
                    edge.setKind(EdgeKind.IMPORTS);
                    edge.setSourceId(fp);
                    edges.add(edge);
                }
            }
        }, tree);

        // __all__ module node
        if (allExports != null) {
            String moduleNodeId = "py:" + fp + ":module";
            CodeNode moduleNode = new CodeNode();
            moduleNode.setId(moduleNodeId);
            moduleNode.setKind(NodeKind.MODULE);
            moduleNode.setLabel(fp);
            moduleNode.setFqn(fp);
            moduleNode.setModule(moduleName);
            moduleNode.setFilePath(fp);
            moduleNode.setLineStart(findLineNumber(text, allMatchStartFinal));
            moduleNode.getProperties().put("__all__", allExports);
            nodes.add(moduleNode);
        }

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
        String fp = ctx.filePath();
        String moduleName = ctx.moduleName();

        Map<Integer, List<String>> decoratorMap = collectDecorators(text);

        Matcher allMatch = ALL_RE.matcher(text);
        List<String> allExports = null;
        int allMatchStart = -1;
        if (allMatch.find()) {
            allMatchStart = allMatch.start();
            String raw = allMatch.group(1);
            allExports = new ArrayList<>();
            Matcher qm = QUOTED_NAME_RE.matcher(raw);
            while (qm.find()) {
                allExports.add(qm.group(1));
            }
        }

        List<int[]> classRanges = new ArrayList<>();
        List<String> classNames = new ArrayList<>();
        Matcher classMatcher = CLASS_RE.matcher(text);
        while (classMatcher.find()) {
            String className = classMatcher.group(1);
            String basesStr = classMatcher.group(2);
            int line = findLineNumber(text, classMatcher.start());

            int lineStartOffset = text.lastIndexOf('\n', classMatcher.start() - 1) + 1;
            int indent = classMatcher.start() - lineStartOffset;

            classNames.add(className);
            classRanges.add(new int[]{classNames.size() - 1, line, indent});

            List<String> annotations = findDecoratorsForLine(decoratorMap, line);

            Map<String, Object> properties = new HashMap<>();
            if (basesStr != null && !basesStr.isBlank()) {
                List<String> bases = new ArrayList<>();
                for (String b : basesStr.split(",")) {
                    String trimmed = b.trim();
                    if (!trimmed.isEmpty()) {
                        bases.add(trimmed);
                    }
                }
                properties.put("bases", bases);
            }
            if (allExports != null && allExports.contains(className)) {
                properties.put("exported", true);
            }

            String nodeId = "py:" + fp + ":class:" + className;
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.CLASS);
            node.setLabel(className);
            node.setFqn(className);
            node.setModule(moduleName);
            node.setFilePath(fp);
            node.setLineStart(line);
            node.setAnnotations(annotations);
            node.setProperties(properties);
            nodes.add(node);

            if (basesStr != null && !basesStr.isBlank()) {
                for (String b : basesStr.split(",")) {
                    String base = b.trim();
                    if (!base.isEmpty()) {
                        CodeEdge edge = new CodeEdge();
                        edge.setId(nodeId + "->extends->" + base);
                        edge.setKind(EdgeKind.EXTENDS);
                        edge.setSourceId(nodeId);
                        edges.add(edge);
                    }
                }
            }
        }

        Matcher funcMatcher = FUNC_RE.matcher(text);
        while (funcMatcher.find()) {
            String indentStr = funcMatcher.group(1);
            boolean isAsync = funcMatcher.group(2) != null;
            String funcName = funcMatcher.group(3);
            int line = findLineNumber(text, funcMatcher.start());
            int indentLen = indentStr.length();

            List<String> annotations = findDecoratorsForLine(decoratorMap, line);

            Map<String, Object> properties = new HashMap<>();
            if (isAsync) {
                properties.put("async", true);
            }
            if (allExports != null && allExports.contains(funcName)) {
                properties.put("exported", true);
            }

            if (indentLen == 0) {
                String nodeId = "py:" + fp + ":func:" + funcName;
                CodeNode node = new CodeNode();
                node.setId(nodeId);
                node.setKind(NodeKind.METHOD);
                node.setLabel(funcName);
                node.setFqn(funcName);
                node.setModule(moduleName);
                node.setFilePath(fp);
                node.setLineStart(line);
                node.setAnnotations(annotations);
                node.setProperties(properties);
                nodes.add(node);
            } else {
                String enclosingClass = findEnclosingClassRegex(classNames, classRanges, line, indentLen);
                if (enclosingClass != null) {
                    String nodeId = "py:" + fp + ":class:" + enclosingClass + ":method:" + funcName;
                    properties.put("class", enclosingClass);
                    CodeNode node = new CodeNode();
                    node.setId(nodeId);
                    node.setKind(NodeKind.METHOD);
                    node.setLabel(enclosingClass + "." + funcName);
                    node.setFqn(enclosingClass + "." + funcName);
                    node.setModule(moduleName);
                    node.setFilePath(fp);
                    node.setLineStart(line);
                    node.setAnnotations(annotations);
                    node.setProperties(properties);
                    nodes.add(node);

                    String classNodeId = "py:" + fp + ":class:" + enclosingClass;
                    CodeEdge edge = new CodeEdge();
                    edge.setId(classNodeId + "->defines->" + nodeId);
                    edge.setKind(EdgeKind.DEFINES);
                    edge.setSourceId(classNodeId);
                    edges.add(edge);
                }
            }
        }

        Matcher importMatcher = IMPORT_RE.matcher(text);
        while (importMatcher.find()) {
            String fromModule = importMatcher.group(1);
            String importNames = importMatcher.group(2);
            if (fromModule != null) {
                CodeEdge edge = new CodeEdge();
                edge.setId(fp + "->imports->" + fromModule);
                edge.setKind(EdgeKind.IMPORTS);
                edge.setSourceId(fp);
                edges.add(edge);
            } else {
                for (String name : importNames.split(",")) {
                    String trimmed = name.trim();
                    if (!trimmed.isEmpty()) {
                        CodeEdge edge = new CodeEdge();
                        edge.setId(fp + "->imports->" + trimmed);
                        edge.setKind(EdgeKind.IMPORTS);
                        edge.setSourceId(fp);
                        edges.add(edge);
                    }
                }
            }
        }

        if (allExports != null) {
            String moduleNodeId = "py:" + fp + ":module";
            CodeNode moduleNode = new CodeNode();
            moduleNode.setId(moduleNodeId);
            moduleNode.setKind(NodeKind.MODULE);
            moduleNode.setLabel(fp);
            moduleNode.setFqn(fp);
            moduleNode.setModule(moduleName);
            moduleNode.setFilePath(fp);
            moduleNode.setLineStart(findLineNumber(text, allMatchStart));
            moduleNode.getProperties().put("__all__", allExports);
            nodes.add(moduleNode);
        }

        return DetectorResult.of(nodes, edges);
    }

    // --- Helper methods ---

    private Map<Integer, List<String>> collectDecorators(String text) {
        Map<Integer, List<String>> result = new HashMap<>();
        Matcher m = DECORATOR_RE.matcher(text);
        while (m.find()) {
            int line = findLineNumber(text, m.start());
            result.computeIfAbsent(line, k -> new ArrayList<>()).add(m.group(2));
        }
        return result;
    }

    private List<String> findDecoratorsForLine(Map<Integer, List<String>> decoratorMap, int targetLine) {
        List<String> decorators = new ArrayList<>();
        int line = targetLine - 1;
        while (decoratorMap.containsKey(line)) {
            decorators.addAll(decoratorMap.get(line));
            line--;
        }
        List<String> reversed = new ArrayList<>(decorators);
        java.util.Collections.reverse(reversed);
        return reversed;
    }

    private static String getBaseClassesText(Python3Parser.ClassdefContext classCtx) {
        if (classCtx.arglist() == null) return null;
        StringBuilder sb = new StringBuilder();
        for (var arg : classCtx.arglist().argument()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(arg.getText());
        }
        return sb.toString();
    }

    /**
     * Get the indentation of a function definition from the source text.
     * For async functions, uses the async keyword position (parent start).
     */
    private static int getIndent(String text, Python3Parser.FuncdefContext funcCtx) {
        int startIndex;
        // For async functions, the ASYNC keyword is on the parent node
        if (funcCtx.getParent() instanceof Python3Parser.Async_funcdefContext asyncCtx) {
            startIndex = asyncCtx.getStart().getStartIndex();
        } else if (funcCtx.getParent() instanceof Python3Parser.Async_stmtContext asyncStmt) {
            startIndex = asyncStmt.getStart().getStartIndex();
        } else {
            startIndex = funcCtx.getStart().getStartIndex();
        }
        // Walk backwards to find the start of the line
        int lineStart = text.lastIndexOf('\n', startIndex - 1) + 1;
        return startIndex - lineStart;
    }

    /**
     * Check if a funcdef is inside an async context (async_funcdef or async_stmt).
     */
    private static boolean isAsyncFunction(Python3Parser.FuncdefContext funcCtx) {
        return funcCtx.getParent() instanceof Python3Parser.Async_funcdefContext
            || funcCtx.getParent() instanceof Python3Parser.Async_stmtContext;
    }

    /**
     * Find the enclosing class for a function at the given line (AST version).
     * Uses the class ranges collected during the walk.
     */
    private static String findEnclosingClass(List<String> classNames, List<int[]> classRanges, int line) {
        for (int i = classRanges.size() - 1; i >= 0; i--) {
            int[] range = classRanges.get(i);
            int startLine = range[1];
            if (line > startLine) {
                return classNames.get(range[0]);
            }
        }
        return null;
    }

    /**
     * Find the enclosing class (regex version with indent tracking).
     */
    private static String findEnclosingClassRegex(List<String> classNames, List<int[]> classRanges,
                                                   int line, int funcIndent) {
        for (int i = classRanges.size() - 1; i >= 0; i--) {
            int[] range = classRanges.get(i);
            int startLine = range[1];
            int classIndent = range[2];
            if (line > startLine && funcIndent > classIndent) {
                return classNames.get(range[0]);
            }
        }
        return null;
    }
}
