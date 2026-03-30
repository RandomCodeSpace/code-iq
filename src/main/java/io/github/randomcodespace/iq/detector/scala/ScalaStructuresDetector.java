package io.github.randomcodespace.iq.detector.scala;

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

@DetectorInfo(
    name = "scala_structures",
    category = "structures",
    description = "Detects Scala classes, traits, objects, case classes, and imports",
    parser = ParserType.REGEX,
    languages = {"scala"},
    nodeKinds = {NodeKind.CLASS, NodeKind.INTERFACE, NodeKind.METHOD, NodeKind.MODULE},
    edgeKinds = {EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS, EdgeKind.IMPORTS}
)
@Component
public class ScalaStructuresDetector extends AbstractAntlrDetector {

    private static final Pattern IMPORT_RE = Pattern.compile("^\\s*import\\s+([\\w.]+)", Pattern.MULTILINE);
    private static final Pattern CLASS_RE = Pattern.compile("^\\s*(?:case\\s+)?class\\s+(\\w+)(?:\\s+extends\\s+(\\w+))?(?:\\s+with\\s+([\\w\\s,]+))?", Pattern.MULTILINE);
    private static final Pattern TRAIT_RE = Pattern.compile("^\\s*trait\\s+(\\w+)", Pattern.MULTILINE);
    private static final Pattern OBJECT_RE = Pattern.compile("^\\s*object\\s+(\\w+)", Pattern.MULTILINE);
    private static final Pattern DEF_RE = Pattern.compile("^\\s*def\\s+(\\w+)\\s*[\\[(]", Pattern.MULTILINE);

    @Override
    public String getName() { return "scala_structures"; }

    @Override
    public Set<String> getSupportedLanguages() { return Set.of("scala"); }
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
            CodeEdge e = new CodeEdge(); e.setId(fp + ":imports:" + m.group(1));
            e.setKind(EdgeKind.IMPORTS); e.setSourceId(fp);
            e.setTarget(new CodeNode(m.group(1), NodeKind.MODULE, m.group(1)));
            edges.add(e);
        }

        m = CLASS_RE.matcher(text);
        while (m.find()) {
            String className = m.group(1);
            String baseClass = m.group(2);
            String traitsStr = m.group(3);
            String nodeId = fp + ":" + className;
            CodeNode n = new CodeNode(); n.setId(nodeId);
            n.setKind(NodeKind.CLASS); n.setLabel(className); n.setFqn(className);
            n.setFilePath(fp); n.setLineStart(findLineNumber(text, m.start()));
            nodes.add(n);
            if (baseClass != null) {
                CodeEdge e = new CodeEdge(); e.setId(nodeId + ":extends:" + baseClass);
                e.setKind(EdgeKind.EXTENDS); e.setSourceId(nodeId);
                e.setTarget(new CodeNode(baseClass, NodeKind.CLASS, baseClass));
                edges.add(e);
            }
            if (traitsStr != null) {
                for (String trait : traitsStr.split(",")) {
                    trait = trait.trim();
                    if (!trait.isEmpty()) {
                        CodeEdge e = new CodeEdge(); e.setId(nodeId + ":implements:" + trait);
                        e.setKind(EdgeKind.IMPLEMENTS); e.setSourceId(nodeId);
                        e.setTarget(new CodeNode(trait, NodeKind.INTERFACE, trait));
                        edges.add(e);
                    }
                }
            }
        }

        m = TRAIT_RE.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            CodeNode n = new CodeNode(); n.setId(fp + ":" + name);
            n.setKind(NodeKind.INTERFACE); n.setLabel(name); n.setFqn(name);
            n.setFilePath(fp); n.setLineStart(findLineNumber(text, m.start()));
            n.getProperties().put("type", "trait");
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

        m = DEF_RE.matcher(text);
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
