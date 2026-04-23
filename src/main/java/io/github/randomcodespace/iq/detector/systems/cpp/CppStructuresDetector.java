package io.github.randomcodespace.iq.detector.systems.cpp;

import io.github.randomcodespace.iq.detector.AbstractAntlrDetector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
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
    name = "cpp_structures",
    category = "structures",
    description = "Detects C/C++ classes, structs, functions, namespaces, and includes",
    parser = ParserType.REGEX,
    languages = {"cpp", "c"},
    nodeKinds = {NodeKind.CLASS, NodeKind.ENUM, NodeKind.METHOD, NodeKind.MODULE},
    edgeKinds = {EdgeKind.EXTENDS, EdgeKind.IMPORTS}
)
@Component
public class CppStructuresDetector extends AbstractAntlrDetector {

    private static final Pattern CLASS_RE = Pattern.compile("(?:template\\s*<[^>]*>\\s*)?class\\s+(\\w+)(?:\\s*:\\s*(?:public|protected|private)\\s+(\\w+))?");
    private static final Pattern STRUCT_RE = Pattern.compile("struct\\s+(\\w+)(?:\\s*:\\s*(?:public|protected|private)\\s+(\\w+))?\\s*\\{");
    private static final Pattern NAMESPACE_RE = Pattern.compile("namespace\\s+(\\w+)\\s*\\{");
    private static final Pattern FUNC_RE = Pattern.compile("^(?:[\\w:*&<>\\s]+)\\s+(\\w+)\\s*\\([^)]*\\)\\s*(?:const\\s*)?\\{", Pattern.MULTILINE);
    private static final Pattern INCLUDE_RE = Pattern.compile("#include\\s+[<\"]([^>\"]+)[>\"]");
    private static final Pattern ENUM_RE = Pattern.compile("enum\\s+(?:class\\s+)?(\\w+)");

    @Override
    public String getName() { return "cpp_structures"; }

    @Override
    public Set<String> getSupportedLanguages() { return Set.of("cpp", "c"); }

    private static boolean isForwardDeclaration(String line) {
        String stripped = line.stripTrailing();
        return stripped.endsWith(";") && !stripped.contains("{");
    }
    @Override
    public DetectorResult detect(DetectorContext ctx) {
        // Skip ANTLR parsing — regex is the primary detection method for this detector
        // ANTLR infrastructure is in place for future enhancement
        return detectWithRegex(ctx);
    }

    @Override
    protected DetectorResult detectWithRegex(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) return DetectorResult.empty();

        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        String fp = ctx.filePath();
        String[] lines = text.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            Matcher m = INCLUDE_RE.matcher(lines[i]);
            if (m.find()) {
                CodeEdge e = new CodeEdge(); e.setId(fp + ":includes:" + m.group(1));
                e.setKind(EdgeKind.IMPORTS); e.setSourceId(fp);
                e.setTarget(new CodeNode(m.group(1), NodeKind.MODULE, m.group(1)));
                edges.add(e);
            }
        }

        for (int i = 0; i < lines.length; i++) {
            Matcher m = NAMESPACE_RE.matcher(lines[i]);
            if (m.find()) {
                CodeNode n = new CodeNode(); n.setId(fp + ":" + m.group(1));
                n.setKind(NodeKind.MODULE); n.setLabel(m.group(1)); n.setFqn(m.group(1));
                n.setFilePath(fp); n.setLineStart(i + 1);
                n.getProperties().put("namespace", true);
                nodes.add(n);
            }
        }

        for (int i = 0; i < lines.length; i++) {
            if (isForwardDeclaration(lines[i])) continue;
            Matcher m = CLASS_RE.matcher(lines[i]);
            if (m.find()) {
                String className = m.group(1);
                String baseClass = m.group(2);
                boolean isTemplate = lines[i].substring(0, Math.min(lines[i].length(), m.start() + m.group(0).length())).contains("template");
                String nodeId = fp + ":" + className;
                CodeNode n = new CodeNode(); n.setId(nodeId);
                n.setKind(NodeKind.CLASS); n.setLabel(className); n.setFqn(className);
                n.setFilePath(fp); n.setLineStart(i + 1);
                if (isTemplate) n.getProperties().put("is_template", true);
                nodes.add(n);
                if (baseClass != null) {
                    CodeEdge e = new CodeEdge(); e.setId(nodeId + ":extends:" + baseClass);
                    e.setKind(EdgeKind.EXTENDS); e.setSourceId(nodeId);
                    e.setTarget(new CodeNode(baseClass, NodeKind.CLASS, baseClass));
                    edges.add(e);
                }
            }
        }

        for (int i = 0; i < lines.length; i++) {
            if (isForwardDeclaration(lines[i])) continue;
            Matcher m = STRUCT_RE.matcher(lines[i]);
            if (m.find()) {
                String structName = m.group(1);
                String baseStruct = m.group(2);
                String nodeId = fp + ":" + structName;
                CodeNode n = new CodeNode(); n.setId(nodeId);
                n.setKind(NodeKind.CLASS); n.setLabel(structName); n.setFqn(structName);
                n.setFilePath(fp); n.setLineStart(i + 1);
                n.getProperties().put("struct", true);
                nodes.add(n);
                if (baseStruct != null) {
                    CodeEdge e = new CodeEdge(); e.setId(nodeId + ":extends:" + baseStruct);
                    e.setKind(EdgeKind.EXTENDS); e.setSourceId(nodeId);
                    e.setTarget(new CodeNode(baseStruct, NodeKind.CLASS, baseStruct));
                    edges.add(e);
                }
            }
        }

        for (int i = 0; i < lines.length; i++) {
            if (isForwardDeclaration(lines[i])) continue;
            Matcher m = ENUM_RE.matcher(lines[i]);
            if (m.find()) {
                CodeNode n = new CodeNode(); n.setId(fp + ":" + m.group(1));
                n.setKind(NodeKind.ENUM); n.setLabel(m.group(1)); n.setFqn(m.group(1));
                n.setFilePath(fp); n.setLineStart(i + 1);
                nodes.add(n);
            }
        }

        Matcher fm = FUNC_RE.matcher(text);
        while (fm.find()) {
            String funcName = fm.group(1);
            int lineNum = text.substring(0, fm.start()).split("\n", -1).length;
            CodeNode n = new CodeNode(); n.setId(fp + ":" + funcName);
            n.setKind(NodeKind.METHOD); n.setLabel(funcName); n.setFqn(funcName);
            n.setFilePath(fp); n.setLineStart(lineNum);
            nodes.add(n);
        }

        return DetectorResult.of(nodes, edges);
    }
}
