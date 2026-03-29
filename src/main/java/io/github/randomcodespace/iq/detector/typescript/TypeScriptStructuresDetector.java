package io.github.randomcodespace.iq.detector.typescript;

import io.github.randomcodespace.iq.detector.AbstractAntlrDetector;
import io.github.randomcodespace.iq.grammar.AntlrParserFactory;
import org.antlr.v4.runtime.tree.ParseTree;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TypeScriptStructuresDetector extends AbstractAntlrDetector {

    private static final Pattern INTERFACE_RE = Pattern.compile(
            "^\\s*(?:export\\s+)?interface\\s+(\\w+)", Pattern.MULTILINE
    );
    private static final Pattern TYPE_RE = Pattern.compile(
            "^\\s*(?:export\\s+)?type\\s+(\\w+)\\s*=", Pattern.MULTILINE
    );
    private static final Pattern CLASS_RE = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:abstract\\s+)?class\\s+(\\w+)", Pattern.MULTILINE
    );
    private static final Pattern FUNC_RE = Pattern.compile(
            "^\\s*(?:export\\s+)?(default\\s+)?(?:(async)\\s+)?function\\s+(\\w+)", Pattern.MULTILINE
    );
    private static final Pattern CONST_FUNC_RE = Pattern.compile(
            "^\\s*(?:export\\s+)?const\\s+(\\w+)\\s*=\\s*(?:(async)\\s+)?\\(", Pattern.MULTILINE
    );
    private static final Pattern ENUM_RE = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:const\\s+)?enum\\s+(\\w+)", Pattern.MULTILINE
    );
    private static final Pattern IMPORT_RE = Pattern.compile(
            "import\\s+.*?\\s+from\\s+['\"]([^'\"]+)['\"]", Pattern.MULTILINE
    );
    private static final Pattern NAMESPACE_RE = Pattern.compile(
            "^\\s*(?:export\\s+)?namespace\\s+(\\w+)", Pattern.MULTILINE
    );

    @Override
    public String getName() {
        return "typescript_structures";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("typescript", "javascript");
    }

    @Override
    protected ParseTree parse(DetectorContext ctx) {
        return AntlrParserFactory.parse(ctx.language(), ctx.content());
    }

    @Override
    protected DetectorResult detectWithAst(ParseTree tree, DetectorContext ctx) {
        return detectWithRegex(ctx);
    }

    @Override
    protected DetectorResult detectWithRegex(DetectorContext ctx) {
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        String text = ctx.content();
        String fp = ctx.filePath();
        String moduleName = ctx.moduleName();
        Set<String> existingIds = new LinkedHashSet<>();

        // Interfaces
        Matcher m = INTERFACE_RE.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            String nodeId = "ts:" + fp + ":interface:" + name;
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.INTERFACE);
            node.setLabel(name);
            node.setFqn(name);
            node.setModule(moduleName);
            node.setFilePath(fp);
            node.setLineStart(findLineNumber(text, m.start()));
            nodes.add(node);
            existingIds.add(nodeId);
        }

        // Type aliases
        m = TYPE_RE.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            String nodeId = "ts:" + fp + ":type:" + name;
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.CLASS);
            node.setLabel(name);
            node.setFqn(name);
            node.setModule(moduleName);
            node.setFilePath(fp);
            node.setLineStart(findLineNumber(text, m.start()));
            node.getProperties().put("type_alias", true);
            nodes.add(node);
            existingIds.add(nodeId);
        }

        // Classes
        m = CLASS_RE.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            String nodeId = "ts:" + fp + ":class:" + name;
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.CLASS);
            node.setLabel(name);
            node.setFqn(name);
            node.setModule(moduleName);
            node.setFilePath(fp);
            node.setLineStart(findLineNumber(text, m.start()));
            nodes.add(node);
            existingIds.add(nodeId);
        }

        // Named functions
        m = FUNC_RE.matcher(text);
        while (m.find()) {
            boolean isDefault = m.group(1) != null;
            boolean isAsync = m.group(2) != null;
            String funcName = m.group(3);
            String nodeId = "ts:" + fp + ":func:" + funcName;

            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.METHOD);
            node.setLabel(funcName);
            node.setFqn(funcName);
            node.setModule(moduleName);
            node.setFilePath(fp);
            node.setLineStart(findLineNumber(text, m.start()));
            if (isDefault) node.getProperties().put("default", true);
            if (isAsync) node.getProperties().put("async", true);
            nodes.add(node);
            existingIds.add(nodeId);
        }

        // Arrow / const functions
        m = CONST_FUNC_RE.matcher(text);
        while (m.find()) {
            String funcName = m.group(1);
            boolean isAsync = m.group(2) != null;
            String nodeId = "ts:" + fp + ":func:" + funcName;
            if (existingIds.contains(nodeId)) continue;

            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.METHOD);
            node.setLabel(funcName);
            node.setFqn(funcName);
            node.setModule(moduleName);
            node.setFilePath(fp);
            node.setLineStart(findLineNumber(text, m.start()));
            if (isAsync) node.getProperties().put("async", true);
            nodes.add(node);
            existingIds.add(nodeId);
        }

        // Enums
        m = ENUM_RE.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            String nodeId = "ts:" + fp + ":enum:" + name;
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.ENUM);
            node.setLabel(name);
            node.setFqn(name);
            node.setModule(moduleName);
            node.setFilePath(fp);
            node.setLineStart(findLineNumber(text, m.start()));
            nodes.add(node);
            existingIds.add(nodeId);
        }

        // Imports
        m = IMPORT_RE.matcher(text);
        while (m.find()) {
            String modulePath = m.group(1);
            CodeEdge edge = new CodeEdge();
            edge.setId(fp + "->imports->" + modulePath);
            edge.setKind(EdgeKind.IMPORTS);
            edge.setSourceId(fp);
            edges.add(edge);
        }

        // Namespaces
        m = NAMESPACE_RE.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            String nodeId = "ts:" + fp + ":namespace:" + name;
            CodeNode node = new CodeNode();
            node.setId(nodeId);
            node.setKind(NodeKind.MODULE);
            node.setLabel(name);
            node.setFqn(name);
            node.setModule(moduleName);
            node.setFilePath(fp);
            node.setLineStart(findLineNumber(text, m.start()));
            nodes.add(node);
            existingIds.add(nodeId);
        }

        return DetectorResult.of(nodes, edges);
    }
}
