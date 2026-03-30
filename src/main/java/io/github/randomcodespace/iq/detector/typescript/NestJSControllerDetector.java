package io.github.randomcodespace.iq.detector.typescript;

import io.github.randomcodespace.iq.detector.AbstractAntlrDetector;
import io.github.randomcodespace.iq.grammar.AntlrParserFactory;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.antlr.v4.runtime.tree.ParseTree;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;
import io.github.randomcodespace.iq.detector.ParserType;

@DetectorInfo(
    name = "typescript.nestjs_controllers",
    category = "endpoints",
    description = "Detects NestJS controllers and their route definitions",
    parser = ParserType.REGEX,
    languages = {"typescript"},
    nodeKinds = {NodeKind.CLASS, NodeKind.ENDPOINT},
    edgeKinds = {EdgeKind.EXPOSES},
    properties = {"framework", "http_method", "protocol"}
)
@Component
public class NestJSControllerDetector extends AbstractAntlrDetector {

    private static final Pattern CONTROLLER_PATTERN = Pattern.compile(
            "@Controller\\(\\s*['\"`]?([^'\"`\\)\\s]*)['\"`]?\\s*\\)(?:\\s*@\\w+\\([^)]*\\))*\\s*\\n\\s*(?:export\\s+)?class\\s+(\\w+)"
    );

    private static final Pattern ROUTE_PATTERN = Pattern.compile(
            "@(Get|Post|Put|Delete|Patch|Options|Head)\\(\\s*['\"`]?([^'\"`\\)\\s]*)['\"`]?\\s*\\)(?:\\s*@\\w+\\([^)]*\\))*\\s*\\n\\s*(?:async\\s+)?(\\w+)"
    );

    @Override
    public String getName() {
        return "typescript.nestjs_controllers";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("typescript");
    }

    @Override
    protected ParseTree parse(DetectorContext ctx) {
        // Use the dedicated TypeScript ANTLR grammar for parsing;
        // detection itself still uses regex for NestJS-specific decorator patterns,
        // but the TS grammar is available for future AST-based enhancement.
        if (ctx.content().length() > 500_000) {
            return null;
        }
        return AntlrParserFactory.parse("typescript", ctx.content());
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        // NestJS detection uses regex for decorator pattern matching.
        // The ANTLR parse tree is triggered via parse() for cache warming
        // (shared with other TS detectors on the same file).
        parse(ctx);
        return detectWithRegex(ctx);
    }

    @Override
    protected DetectorResult detectWithRegex(DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        String text = ctx.content();
        String filePath = ctx.filePath();
        String moduleName = ctx.moduleName();

        // Find controllers
        List<int[]> controllerRanges = new ArrayList<>(); // [line, index into names/paths]
        List<String> ctrlNames = new ArrayList<>();
        List<String> ctrlPaths = new ArrayList<>();

        Matcher matcher = CONTROLLER_PATTERN.matcher(text);
        while (matcher.find()) {
            String basePath = matcher.group(1) != null ? matcher.group(1) : "";
            String className = matcher.group(2);
            int line = findLineNumber(text, matcher.start());

            ctrlNames.add(className);
            ctrlPaths.add(basePath);
            controllerRanges.add(new int[]{line, ctrlNames.size() - 1});

            String classId = "class:" + filePath + "::" + className;
            CodeNode node = new CodeNode();
            node.setId(classId);
            node.setKind(NodeKind.CLASS);
            node.setLabel(className);
            node.setFqn(filePath + "::" + className);
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(line);
            node.getAnnotations().add("@Controller");
            node.getProperties().put("framework", "nestjs");
            node.getProperties().put("stereotype", "controller");
            nodes.add(node);
        }

        // Find routes
        matcher = ROUTE_PATTERN.matcher(text);
        while (matcher.find()) {
            int routeLine = findLineNumber(text, matcher.start());

            // Find enclosing controller
            String currentClass = "";
            String basePath = "";
            for (int[] range : controllerRanges) {
                if (range[0] <= routeLine) {
                    currentClass = ctrlNames.get(range[1]);
                    basePath = ctrlPaths.get(range[1]);
                }
            }

            String method = matcher.group(1).toUpperCase();
            String path = matcher.group(2) != null ? matcher.group(2) : "";
            String funcName = matcher.group(3);

            String fullPath = ("/" + basePath + "/" + path)
                    .replaceAll("//+", "/");
            if (fullPath.length() > 1 && fullPath.endsWith("/")) {
                fullPath = fullPath.substring(0, fullPath.length() - 1);
            }
            if (fullPath.isEmpty()) fullPath = "/";

            String nodeId = "endpoint:" + (moduleName != null ? moduleName : "") + ":" + method + ":" + fullPath;
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.ENDPOINT);
            node.setLabel(method + " " + fullPath);
            node.setFqn(filePath + "::" + funcName);
            node.setModule(moduleName);
            node.setFilePath(filePath);
            node.setLineStart(routeLine);
            node.getProperties().put("protocol", "REST");
            node.getProperties().put("http_method", method);
            node.getProperties().put("path_pattern", fullPath);
            node.getProperties().put("framework", "nestjs");
            nodes.add(node);

            if (!currentClass.isEmpty()) {
                String classId = "class:" + filePath + "::" + currentClass;
                CodeEdge edge = new CodeEdge();
                edge.setId(classId + "->exposes->" + nodeId);
                edge.setKind(EdgeKind.EXPOSES);
                edge.setSourceId(classId);
                edges.add(edge);
            }
        }

        return DetectorResult.of(nodes, edges);
    }
}
