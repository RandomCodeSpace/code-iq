package io.github.randomcodespace.iq.detector.kotlin;

import io.github.randomcodespace.iq.detector.AbstractAntlrDetector;
import io.github.randomcodespace.iq.grammar.AntlrParserFactory;
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

// Kotlin Compiler API (kotlin-compiler-embeddable) evaluated 2026-03-28 but regex/ANTLR
// approach provides sufficient detection quality for Kotlin structures. The compiler JAR
// adds 50-70MB for only 2 Kotlin detectors — not justified. Revisit if complex semantic
// analysis (type resolution, coroutine flow tracking) is needed in the future.
@DetectorInfo(
    name = "kotlin_structures",
    category = "structures",
    description = "Detects Kotlin classes, interfaces, objects, functions, and imports",
    parser = ParserType.REGEX,
    languages = {"kotlin"},
    nodeKinds = {NodeKind.CLASS, NodeKind.INTERFACE, NodeKind.METHOD, NodeKind.MODULE},
    edgeKinds = {EdgeKind.EXTENDS, EdgeKind.IMPORTS}
)
@Component
public class KotlinStructuresDetector extends AbstractAntlrDetector {

    private static final Pattern IMPORT_RE = Pattern.compile("^\\s*import\\s+([\\w.]+)", Pattern.MULTILINE);
    private static final Pattern CLASS_RE = Pattern.compile("^\\s*(?:(?:data|open|abstract|sealed|enum|annotation|value|inline)\\s+)*class\\s+(\\w+)(?:\\s*(?:\\(.*?\\))?\\s*:\\s*([\\w\\s,.<>]+))?", Pattern.MULTILINE);
    private static final Pattern INTERFACE_RE = Pattern.compile("^\\s*interface\\s+(\\w+)", Pattern.MULTILINE);
    private static final Pattern FUN_RE = Pattern.compile("^\\s*(?:(?:override|inline|private|protected|internal|public)\\s+)*(?:fun|suspend\\s+fun)\\s+(\\w+)\\s*\\(", Pattern.MULTILINE);
    private static final Pattern OBJECT_RE = Pattern.compile("^\\s*object\\s+(\\w+)", Pattern.MULTILINE);

    @Override
    public String getName() { return "kotlin_structures"; }

    @Override
    public Set<String> getSupportedLanguages() { return Set.of("kotlin"); }
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

        Matcher m = IMPORT_RE.matcher(text);
        while (m.find()) {
            String target = m.group(1);
            CodeEdge e = new CodeEdge(); e.setId(fp + ":imports:" + target);
            e.setKind(EdgeKind.IMPORTS); e.setSourceId(fp);
            e.setTarget(new CodeNode(target, NodeKind.MODULE, target));
            edges.add(e);
        }

        m = CLASS_RE.matcher(text);
        while (m.find()) {
            String className = m.group(1);
            String supertypesStr = m.group(2);
            String nodeId = fp + ":" + className;
            CodeNode n = new CodeNode(); n.setId(nodeId);
            n.setKind(NodeKind.CLASS); n.setLabel(className); n.setFqn(className);
            n.setFilePath(fp); n.setLineStart(findLineNumber(text, m.start()));
            nodes.add(n);
            if (supertypesStr != null) {
                for (String st : supertypesStr.split(",")) {
                    st = st.trim().split("\\(")[0].split("<")[0].trim();
                    if (!st.isEmpty()) {
                        CodeEdge e = new CodeEdge(); e.setId(nodeId + ":extends:" + st);
                        e.setKind(EdgeKind.EXTENDS); e.setSourceId(nodeId);
                        e.setTarget(new CodeNode(st, NodeKind.CLASS, st));
                        edges.add(e);
                    }
                }
            }
        }

        m = INTERFACE_RE.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            CodeNode n = new CodeNode(); n.setId(fp + ":" + name);
            n.setKind(NodeKind.INTERFACE); n.setLabel(name); n.setFqn(name);
            n.setFilePath(fp); n.setLineStart(findLineNumber(text, m.start()));
            nodes.add(n);
        }

        m = OBJECT_RE.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            CodeNode n = new CodeNode(); n.setId(fp + ":" + name);
            n.setKind(NodeKind.CLASS); n.setLabel(name); n.setFqn(name);
            n.setFilePath(fp); n.setLineStart(findLineNumber(text, m.start()));
            n.getProperties().put("type", "object");
            nodes.add(n);
        }

        m = FUN_RE.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            CodeNode n = new CodeNode(); n.setId(fp + ":" + name);
            n.setKind(NodeKind.METHOD); n.setLabel(name); n.setFqn(name);
            n.setFilePath(fp); n.setLineStart(findLineNumber(text, m.start()));
            nodes.add(n);
        }

        return DetectorResult.of(nodes, edges);
    }
}
